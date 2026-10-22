package com.wepwawet.proxy

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * WeControlServer — TCP 7889 listener that implements the REAL WE pairing protocol.
 *
 * PROTOCOL VERIFIED FROM REAL WIRESHARK CAPTURE (2026-10-22) — see
 * docs/RE_FINDINGS_VERIFIED.md for the full decode.
 *
 * Wire format: 4-byte LITTLE-ENDIAN length prefix + N bytes of data.
 *
 * PC → phone (in order):
 *   Frame 0: length=3 + 3 bytes = RSA exponent (BE BigInteger of 65537 = `01 00 01`)
 *   Frame 1: length=128 + 128 bytes = RSA-1024 modulus (raw BE bytes, no sign byte)
 *
 * Phone → PC (in order):
 *   Frame 0: length=128 + 128B RSA-encrypted AES key (RSA-1024 = 128-byte ciphertext)
 *   Frame 1: length=128 + 128B RSA-encrypted AES IV (16-byte IV padded to RSA block)
 *   Frame 2: length=128 + 128B RSA-encrypted ??? (purpose TBD — possibly MAC/session token)
 *   Frame 3: length=N + N bytes AES-encrypted EncAuthRequest JSON
 *
 * After pairing, phone sends literal ASCII "heartbeat\n" (10 bytes, NO length prefix)
 * every ~3 seconds. The server must detect and skip these frames, otherwise
 * readLeInt would misread 'h','e','a','r' as 0x72616568 = ~1.9 GiB and bail out.
 *
 * Critical: RSA is RSA-1024 (128-byte modulus), NOT RSA-2048. The phone expects
 * exactly 128 bytes of modulus data.
 *
 * Critical: Length prefixes are 4-byte LITTLE-ENDIAN (NOT Java's DataInputStream.readInt
 * default which is BIG-ENDIAN). Use writeLeInt / readLeInt helpers.
 */
class WeControlServer(
    private val context: Context,
) {

    companion object {
        const val PORT = 7889
        // VERIFIED from real capture: PC sent 128-byte modulus = RSA-1024
        private const val RSA_KEY_SIZE = 1024
        private const val RSA_BLOCK_SIZE = 128  // bytes per RSA-1024 ciphertext
        // VERIFIED from real capture: phone sent 176-byte AES ciphertext = 11 AES blocks
        private const val AES_IV_SIZE = 16
        // From decompiled TcpClient.java:
        private const val MAX_SAFE_DATA_LENGTH = 1048592  // ~1 MiB
        // From decompiled TransferService.java:
        private const val CONNECTION_PROTOCOL_VERSION = 4
        private const val MPKG_SUPPORT_VERSION = "PKGM0020"
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val connections = ConcurrentHashMap<String, ConnectionHandler>()
    private val pairedDevices = PairedDeviceStore(context)
    private val gson = Gson()

    // RSA keypair — generated on first start, reused thereafter
    @Volatile private var rsaKeyPair: KeyPair? = null

    // Current pairing PIN
    @Volatile var currentPin: String = "1234"

    fun start() {
        if (running) {
            WepWawetLogger.proxyWarn("WeControlServer.start called but already running")
            return
        }
        running = true

        // Generate RSA-1024 keypair (matches real protocol)
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }
        rsaKeyPair = gen.generateKeyPair()
        val pub = rsaKeyPair!!.public as RSAPublicKey
        WepWawetLogger.proxy("WeControlServer RSA keypair generated",
            "bits" to pub.modulus.bitLength(),
            "modulus_bytes" to pub.modulus.toByteArray().size,
            "exponent" to pub.publicExponent.toString(16))

        serverSocket = ServerSocket().apply {
            bind(InetSocketAddress(PORT))
            soTimeout = 1500  // wake up periodically
        }
        thread(start = true, name = "wep-control-listen", isDaemon = true) {
            acceptLoop()
        }
        WepWawetLogger.proxy("WeControlServer listening on TCP $PORT",
            "rsa_bits" to RSA_KEY_SIZE,
            "pin" to currentPin,
            "protocol_version" to CONNECTION_PROTOCOL_VERSION,
            "mpkg_version" to MPKG_SUPPORT_VERSION)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        WepWawetLogger.proxy("WeControlServer stopped")
    }

    private fun acceptLoop() {
        val srv = serverSocket ?: return
        while (running) {
            try {
                val client = srv.accept()
                val remoteAddr = (client.remoteSocketAddress as? InetSocketAddress)?.hostString ?: "unknown"
                val handler = ConnectionHandler(client, remoteAddr)
                connections[remoteAddr] = handler
                thread(start = true, name = "wep-conn-$remoteAddr", isDaemon = true) {
                    handler.run()
                }
            } catch (_: java.net.SocketTimeoutException) {
                // normal — loop back
            } catch (e: Exception) {
                if (running) {
                    WepWawetLogger.proxyWarn("accept error",
                        "exception" to e.javaClass.name)
                }
            }
        }
    }

    // ====== Per-connection handler ======

    private inner class ConnectionHandler(
        private val socket: Socket,
        private val remoteAddr: String,
    ) {
        private var paired: Boolean = false
        private var deviceId: String? = null
        private var aesKey: SecretKey? = null
        private var aesIv: IvParameterSpec? = null

        fun run() {
            try {
                socket.use {
                    it.keepAlive = true
                    it.soTimeout = 30000  // 30s for handshake
                    val input = DataInputStream(BufferedInputStream(it.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(it.getOutputStream()))

                    WepWawetLogger.proxy("connection accepted", "remote" to remoteAddr)

                    // === PC → phone: Send RSA exponent FIRST (3 bytes `01 00 01`) ===
                    val pubKey = rsaKeyPair!!.public as RSAPublicKey
                    val expBytes = pubKey.publicExponent.toByteArray()
                    // BigInteger.toByteArray may include a sign byte (0x00) — strip leading zeros
                    val expStripped = stripLeadingZero(expBytes)
                    WepWawetLogger.proxyDebug("Sending RSA exponent",
                        "exponent" to pubKey.publicExponent.toString(16),
                        "wire_bytes" to expStripped.size,
                        "wire_hex" to expStripped.joinToString("") { "%02x".format(it) })
                    writeLeInt(output, expStripped.size)
                    output.write(expStripped)
                    output.flush()

                    // === PC → phone: Send RSA modulus (128 bytes for RSA-1024) ===
                    val modBytes = pubKey.modulus.toByteArray()
                    val modStripped = stripLeadingZero(modBytes)
                    // Pad to exactly 128 bytes if needed (left-pad with zeros)
                    val modPadded = if (modStripped.size < RSA_BLOCK_SIZE) {
                        ByteArray(RSA_BLOCK_SIZE - modStripped.size) + modStripped
                    } else {
                        modStripped
                    }
                    WepWawetLogger.proxyDebug("Sending RSA modulus",
                        "modulus_bits" to pubKey.modulus.bitLength(),
                        "wire_bytes" to modPadded.size,
                        "wire_hex_first_16" to modPadded.take(16).joinToString("") { "%02x".format(it) })
                    writeLeInt(output, modPadded.size)
                    output.write(modPadded)
                    output.flush()

                    // === Phone → PC: Receive 3 RSA-encrypted frames (128B each) ===
                    val encAesKey = readLengthPrefixed(input, "RSA-encrypted AES key")
                        ?: return
                    val encIv = readLengthPrefixed(input, "RSA-encrypted IV")
                        ?: return
                    val encThird = readLengthPrefixed(input, "RSA-encrypted third block")
                        ?: return

                    // Decrypt with RSA/ECB/PKCS1Padding (Java's default RSA cipher)
                    val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                    rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair!!.private)
                    val aesKeyBytes = rsaCipher.doFinal(encAesKey)
                    val ivBytes = rsaCipher.doFinal(encIv)
                    val thirdBlock = rsaCipher.doFinal(encThird)

                    aesKey = SecretKeySpec(aesKeyBytes, "AES")
                    aesIv = IvParameterSpec(ivBytes)

                    WepWawetLogger.proxy("AES key exchange complete",
                        "aes_key_bits" to (aesKeyBytes.size * 8),
                        "iv_bytes" to ivBytes.size,
                        "third_block_bytes" to thirdBlock.size,
                        "third_block_hex_first_32" to thirdBlock.take(32)
                            .joinToString("") { "%02x".format(it) })

                    // === Phone → PC: Receive AES-encrypted EncAuthRequest ===
                    val encAuthReq = readLengthPrefixed(input, "AES-encrypted EncAuthRequest")
                        ?: return

                    val decCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    decCipher.init(Cipher.DECRYPT_MODE, aesKey, aesIv)
                    val authReqJson = String(decCipher.doFinal(encAuthReq), Charsets.UTF_8)
                    WepWawetLogger.proxy("Received EncAuthRequest", "json" to authReqJson)

                    val authReq = gson.fromJson(authReqJson, EncAuthRequest::class.java)
                    val pinValid = authReq.pin == currentPin
                    WepWawetLogger.proxy("PIN verification",
                        "received_pin" to (authReq.pin ?: ""),
                        "expected_pin" to currentPin,
                        "valid" to pinValid,
                        "device_name" to (authReq.deviceUserName ?: authReq.name ?: "Unknown"),
                        "display" to "${authReq.displayWidth}x${authReq.displayHeight}",
                        "mpkg_version" to (authReq.mpkgVersion ?: ""),
                        "app_version" to (authReq.appVersion ?: ""))

                    // === PC → phone: Send AES-encrypted EncAuthResponse ===
                    val status = if (pinValid) "ok" else "invalid_pin"
                    val respJson = gson.toJson(EncAuthResponse(status))
                    val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    encCipher.init(Cipher.ENCRYPT_MODE, aesKey, aesIv)
                    val respBytes = encCipher.doFinal(respJson.toByteArray(Charsets.UTF_8))
                    writeLeInt(output, respBytes.size)
                    output.write(respBytes)
                    output.flush()
                    WepWawetLogger.proxyDebug("Sent EncAuthResponse",
                        "status" to status, "ciphertext_bytes" to respBytes.size)

                    if (!pinValid) {
                        WepWawetLogger.proxyWarn("Pairing FAILED — invalid PIN")
                        return
                    }

                    paired = true
                    deviceId = authReq.deviceUserName ?: authReq.name ?: remoteAddr
                    // TODO: store the pairing token (current AES key/IV) in PairedDeviceStore
                    // so subsequent connections can resume without re-pairing. For now we
                    // just log success — the connection stays open and handles commands.
                    WepWawetLogger.proxy("Pairing SUCCESSFUL",
                        "device" to deviceId!!, "remote" to remoteAddr)

                    // === Command loop (skip heartbeats, dispatch commands) ===
                    it.soTimeout = 0  // blocking reads
                    commandLoop(input, output)
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("connection handler ended",
                    "remote" to remoteAddr,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            } finally {
                connections.remove(remoteAddr)
            }
        }

        fun close() {
            runCatching { socket.close() }
        }

        /**
         * Command loop — reads length-prefixed AES-encrypted commands, skipping
         * literal "heartbeat\n" frames that the phone sends every ~3s.
         */
        private fun commandLoop(input: DataInputStream, output: DataOutputStream) {
            WepWawetLogger.proxy("Entering command loop")
            var heartbeatCount = 0
            var messageCount = 0
            val decCmdCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            decCmdCipher.init(Cipher.DECRYPT_MODE, aesKey, aesIv)
            val encCmdCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            encCmdCipher.init(Cipher.ENCRYPT_MODE, aesKey, aesIv)

            while (running && paired && !socket.isClosed) {
                try {
                    val (kind, payload) = readMessageOrHeartbeat(input)
                    when (kind) {
                        "heartbeat" -> {
                            heartbeatCount++
                            if (heartbeatCount % 10 == 1) {
                                WepWawetLogger.proxyDebug("Heartbeat",
                                    "count" to heartbeatCount)
                            }
                        }
                        "invalid" -> {
                            WepWawetLogger.proxyWarn("Invalid frame in command loop")
                            break
                        }
                        else -> {
                            val msgBytes = payload ?: continue
                            messageCount++
                            val plaintext = decCmdCipher.doFinal(msgBytes)
                            val cmdJson = String(plaintext, Charsets.UTF_8)
                            WepWawetLogger.proxy("Received command",
                                "index" to messageCount,
                                "length" to msgBytes.size,
                                "json" to cmdJson.take(500))
                            // Send a 32-byte ack (matches real protocol's 32-byte responses)
                            val resp = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
                            val padLen = 32 - (resp.size % 32)
                            val respPadded = resp + ByteArray(padLen) { padLen.toByte() }
                            val ct = encCmdCipher.doFinal(respPadded)
                            writeLeInt(output, ct.size)
                            output.write(ct)
                            output.flush()
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // normal
                } catch (e: Exception) {
                    if (running) {
                        WepWawetLogger.proxyWarn("Command loop ended",
                            "exception" to e.javaClass.name,
                            "heartbeats_seen" to heartbeatCount,
                            "messages_seen" to messageCount)
                    }
                    break
                }
            }
            WepWawetLogger.proxy("Command loop ended",
                "heartbeats_seen" to heartbeatCount,
                "messages_seen" to messageCount)
        }
    }

    // ====== Wire format helpers (LITTLE-ENDIAN — verified from decompiled source) ======

    /**
     * Write a 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.writeInt exactly.
     * DO NOT use DataOutputStream.writeInt() — that's BIG-ENDIAN.
     */
    private fun writeLeInt(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xFF)
        output.writeByte((value shr 8) and 0xFF)
        output.writeByte((value shr 16) and 0xFF)
        output.writeByte((value shr 24) and 0xFF)
    }

    /**
     * Read a 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.readInt exactly.
     */
    private fun readLeInt(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or
               ((b[1].toInt() and 0xFF) shl 8) or
               ((b[2].toInt() and 0xFF) shl 16) or
               ((b[3].toInt() and 0xFF) shl 24)
    }

    /**
     * Read a length-prefixed message: 4-byte LE length + N bytes.
     */
    private fun readLengthPrefixed(input: DataInputStream, label: String): ByteArray? {
        val length = readLeInt(input)
        WepWawetLogger.proxyDebug("Reading length-prefixed: $label",
            "length" to length)
        if (length <= 0 || length > MAX_SAFE_DATA_LENGTH) {
            WepWawetLogger.proxyError("Invalid $label length: $length")
            return null
        }
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    /**
     * Read a length-prefixed message OR skip a literal "heartbeat\n" frame.
     *
     * The phone sends "heartbeat\n" (10 ASCII bytes, NO length prefix) every ~3s.
     * Without this, readLeInt would read 'h','e','a','r' as 0x72616568 = ~1.9 GiB
     * and bail out of the command loop.
     */
    private fun readMessageOrHeartbeat(input: DataInputStream): Pair<String, ByteArray?> {
        val header = ByteArray(4)
        input.readFully(header)
        // Check for 'h','e','a','r' = first 4 bytes of "heartbeat\n"
        if (header[0] == 0x68.toByte() && header[1] == 0x65.toByte() &&
            header[2] == 0x61.toByte() && header[3] == 0x72.toByte()) {
            // Consume the remaining 6 bytes of "heartbeat\n": t,b,e,a,t,\n
            val rest = ByteArray(6)
            input.readFully(rest)
            return "heartbeat" to null
        }
        // Otherwise treat as little-endian length prefix
        val length = (header[0].toInt() and 0xFF) or
                     ((header[1].toInt() and 0xFF) shl 8) or
                     ((header[2].toInt() and 0xFF) shl 16) or
                     ((header[3].toInt() and 0xFF) shl 24)
        if (length <= 0 || length > MAX_SAFE_DATA_LENGTH) {
            return "invalid" to null
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return "message" to payload
    }

    /**
     * Strip leading zero bytes from a BigInteger.toByteArray() result.
     * The real protocol sends the modulus as raw bytes WITHOUT the sign byte.
     */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
            start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Push a .mpkg file to a paired device.
     * TODO: implement the actual file-push wire format (pending RE of post-pairing commands).
     */
    fun pushMpkgToDevice(mpkgFile: File, deviceId: String?) {
        val target = deviceId?.let { listOf(it) } ?: connections.keys.toList()
        for (id in target) {
            val handler = connections[id] ?: continue
            WepWawetLogger.proxyDebug("pushMpkgToDevice (TODO: wire format)",
                "device" to id, "file" to mpkgFile.name, "file_size" to mpkgFile.length())
        }
    }
}

/**
 * EncAuthRequest — JSON the phone sends (AES-encrypted) to authenticate.
 *
 * Fields from decompiled TcpClient$EncAuthRequest:
 *   pin: String              — 4-digit PIN shown on the "PC"
 *   name: String             — device name
 *   deviceUserName: String   — custom device name entered by user
 *   displayWidth: Int        — phone screen width
 *   displayHeight: Int       — phone screen height
 *   version: Int             — protocol version (default 4)
 *   mpkgVersion: String      — supported .mpkg version ("PKGM0020" current)
 *   appVersion: String       — WE Android app version
 */
data class EncAuthRequest(
    val pin: String? = null,
    val name: String? = null,
    val deviceUserName: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val version: Int? = null,
    val mpkgVersion: String? = null,
    val appVersion: String? = null,
)

/**
 * EncAuthResponse — JSON the "PC" sends (AES-encrypted) back to the phone.
 *   status: String — "ok" for success, anything else for failure
 */
data class EncAuthResponse(
    var status: String = "",
)
