package com.wepwawet.pairing

import android.util.Log
import com.google.gson.Gson
import com.wepwawet.log.WepWawetLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * WE Pairing Server — acts as the "PC" side of the WE pairing protocol.
 *
 * This is the heart of the new WepWawet architecture. Instead of emulating
 * Wine+Box64+Steam+WE, this server NATIVELY speaks the WE pairing protocol.
 * The official WE Android app discovers and pairs with this server exactly
 * as it would pair with a real PC running Wallpaper Engine.
 *
 * Protocol (reverse-engineered from the WE Android APK decompiled with jadx):
 *
 * 1. DISCOVERY (UDP 7884):
 *    - Server broadcasts "WallpaperEngine/Ping:<pid>:<hostname>:1:<ifaceId>"
 *      via UDP multicast to 239.100.0.1:7884 every 5 seconds
 *    - Phone's DiscoverService listens and finds the server
 *
 * 2. PAIRING (TCP 7889):
 *    - Phone connects to server on TCP 7889
 *    - Server sends RSA public key (exponent + modulus as BigInteger)
 *    - Phone generates AES key + IV
 *    - Phone encrypts AES key with server's RSA public key → sends to server
 *    - Phone encrypts EncAuthRequest (JSON) with AES/CBC/PKCS5Padding → sends to server
 *    - Server decrypts AES key with RSA private key
 *    - Server decrypts EncAuthRequest with AES
 *    - Server verifies PIN
 *    - Server encrypts EncAuthResponse (JSON) with AES → sends to phone
 *    - All subsequent communication is AES-encrypted
 *
 * 3. FILE TRANSFER:
 *    - Phone requests wallpaper download
 *    - Server sends .mpkg file via AES-encrypted ranged transfer
 *    - Uses writeInt(length) + ciphertext format for all messages
 *
 * Wire format for encrypted messages:
 *   writeInt(ciphertext_length)   // 4 bytes, LITTLE-ENDIAN (verified from decompiled
 *                                 // TcpClient$Companion.writeInt — output.writeByte(value & 255)
 *                                 // then >> 8, >> 16, >> 24 — that's little-endian!)
 *   write(ciphertext)             // AES/CBC/PKCS5Padding encrypted JSON
 *
 * CRITICAL: The WE Android app's TcpClient$Companion.readInt/writeInt use
 * LITTLE-ENDIAN byte order (ByteBuffer.order(LITTLE_ENDIAN) and shift-down
 * writeByte calls). Java's DataInputStream.readInt() and DataOutputStream.writeInt()
 * use BIG-ENDIAN. So we MUST use our own readLeInt()/writeLeInt() helpers
 * for ALL length prefixes, otherwise the server misreads every message length
 * and pairing fails silently.
 *
 * Heartbeat: After pairing succeeds, the phone's connector thread sends
 * "heartbeat\n" (10 ASCII bytes, no length prefix) every ~3 seconds.
 * The server must detect and skip these frames, otherwise readLeInt() would
 * interpret 'h','e','a','r' as a 4-byte length and bail out.
 *
 * Constants from decompiled TcpClient.java:
 *   MAX_SAFE_COMMAND_LENGTH = 4096
 *   MAX_SAFE_DATA_LENGTH = 1048572  (1 MB)
 */
class WePairingServer {

    companion object {
        private const val TAG = "WEP-PAIRING"
        const val TCP_PORT = 7889
        const val MAX_SAFE_COMMAND_LENGTH = 4096
        const val MAX_SAFE_DATA_LENGTH = 1048592  // ~1 MiB
        // VERIFIED from real capture: PC sent 128-byte modulus = RSA-1024
        // (was 2048 in earlier code — WRONG)
        private const val RSA_KEY_SIZE = 1024
        private const val RSA_BLOCK_SIZE = 128  // bytes per RSA-1024 ciphertext
        // AES key size — actual size confirmed by Frida hooks pending;
        // AES supports 128/192/256-bit. Default 256.
        private const val AES_KEY_SIZE = 256
        private const val AES_IV_SIZE = 16
        // VERIFIED from decompiled TransferService.java
        private const val CONNECTION_PROTOCOL_VERSION = 4
        private const val MPKG_SUPPORT_VERSION = "PKGM0020"
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var rsaKeyPair: KeyPair? = null

    // Current pairing state
    private var currentPin: String = ""
    private var currentAesKey: SecretKey? = null
    private var currentIv: IvParameterSpec? = null
    private var currentClient: Socket? = null

    // Callbacks
    var onPinRequested: (() -> String)? = null
    var onPairingSuccess: ((String) -> Unit)? = null  // deviceName
    var onPairingFailure: ((String) -> Unit)? = null  // errorMessage
    var onWallpaperRequested: ((String) -> String?)? = null  // wallpaperId -> mpkgFilePath

    /**
     * Generate an RSA keypair for pairing.
     * The public key will be sent to the phone during pairing.
     * The private key is used to decrypt the phone's AES key.
     */
    fun generateRsaKeyPair(): KeyPair {
        WepWawetLogger.proxy("Generating RSA keypair ($RSA_KEY_SIZE bits)")
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE)
        val keyPair = generator.generateKeyPair()
        rsaKeyPair = keyPair

        // Log the public key components (for debugging)
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        WepWawetLogger.proxyDebug("RSA keypair generated",
            "modulus_bits" to pubKey.modulus.bitLength(),
            "exponent" to pubKey.publicExponent.toString(16).take(16))

        return keyPair
    }

    /**
     * Get the RSA public key as BigInteger components.
     * These are sent to the phone during pairing.
     */
    fun getRsaPublicKeyComponents(): Pair<BigInteger, BigInteger> {
        val keyPair = rsaKeyPair ?: generateRsaKeyPair()
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        return Pair(pubKey.modulus, pubKey.publicExponent)
    }

    /**
     * Start the pairing server.
     * Listens on TCP 7889 for incoming connections from the WE Android app.
     */
    fun start(pin: String) {
        if (running) {
            WepWawetLogger.proxyWarn("Pairing server already running")
            return
        }
        running = true
        currentPin = pin

        // Generate RSA keypair
        generateRsaKeyPair()

        WepWawetLogger.proxy("Pairing server starting",
            "port" to TCP_PORT,
            "pin" to pin,
            "rsa_key_size" to RSA_KEY_SIZE)

        thread(start = true, name = "wep-pairing-server", isDaemon = true) {
            try {
                serverSocket = ServerSocket().apply {
                    bind(java.net.InetSocketAddress(TCP_PORT))
                    soTimeout = 0  // block forever
                }
                WepWawetLogger.proxy("Pairing server listening on TCP $TCP_PORT")

                while (running) {
                    val client = serverSocket?.accept() ?: break
                    WepWawetLogger.proxy("Incoming connection",
                        "remote" to (client.remoteSocketAddress.toString()))

                    thread(start = true, name = "wep-pairing-conn", isDaemon = true) {
                        handleConnection(client)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    WepWawetLogger.proxyError("Pairing server error",
                        "exception" to e.javaClass.name, exception = e)
                }
            } finally {
                serverSocket?.close()
                running = false
                WepWawetLogger.proxy("Pairing server stopped")
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        currentClient?.close()
        currentClient = null
        currentAesKey = null
        currentIv = null
        WepWawetLogger.proxy("Pairing server stop requested")
    }

    /**
     * Write a 4-byte LITTLE-ENDIAN integer to the output stream.
     *
     * Mirrors the WE Android app's TcpClient$Companion.writeInt exactly:
     *   output.writeByte(value & 255)
     *   output.writeByte((value >> 8) & 255)
     *   output.writeByte((value >> 16) & 255)
     *   output.writeByte((value >> 24) & 255)
     *
     * If you use DataOutputStream.writeInt() instead, that uses BIG-ENDIAN,
     * and the phone will misread every length prefix — pairing will fail
     * silently with a 0 or huge bogus length.
     */
    private fun writeLeInt(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xFF)
        output.writeByte((value shr 8) and 0xFF)
        output.writeByte((value shr 16) and 0xFF)
        output.writeByte((value shr 24) and 0xFF)
    }

    /**
     * Strip leading zero bytes from a BigInteger.toByteArray() result.
     * The real protocol sends the modulus as raw bytes WITHOUT the sign byte
     * that Java's BigInteger.toByteArray() prepends for positive numbers with
     * the high bit set.
     */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
            start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Read a length-prefixed message: 4-byte LE length + N bytes.
     * Returns null on invalid length (caller should close the connection).
     */
    private fun readLengthPrefixed(input: DataInputStream, label: String): ByteArray? {
        val length = readLeInt(input)
        WepWawetLogger.proxyDebug("Reading length-prefixed: $label", "length" to length)
        if (length <= 0 || length > MAX_SAFE_DATA_LENGTH) {
            WepWawetLogger.proxyError("Invalid $label length: $length")
            return null
        }
        val data = ByteArray(length)
        input.readFully(data)
        return data
    }

    /**
     * Read a 4-byte LITTLE-ENDIAN integer from the input stream.
     *
     * Mirrors the WE Android app's TcpClient$Companion.readInt exactly:
     *   byte[] bArr = new byte[4];
     *   input.read(bArr, 0, 4);
     *   return ByteBuffer.wrap(bArr).order(LITTLE_ENDIAN).getInt();
     *
     * If you use DataInputStream.readInt() instead, that uses BIG-ENDIAN,
     * and you'll misread every length prefix from the phone.
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
     * Read a length-prefixed message, gracefully skipping 'heartbeat\n'
     * keepalive frames that the phone's connector thread sends every ~3s
     * after pairing. Without this, readLeInt() would read 'h','e','a','r'
     * (0x68, 0x65, 0x61, 0x72) → little-endian 0x72616568 = 1,919,014,504,
     * which exceeds MAX_SAFE_DATA_LENGTH and breaks the command loop.
     *
     * Strategy: peek the first 4 bytes. If they spell ASCII "hear",
     * consume the remaining 6 bytes ("tbeat\n") and return a sentinel
     * indicating a heartbeat was seen.
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
     * Handle a single pairing connection from the WE Android app.
     *
     * This implements the full pairing handshake:
     * 1. Send RSA public key (modulus + exponent, length-prefixed LE)
     * 2. Receive encrypted AES key (RSA-encrypted, length-prefixed LE)
     * 3. Receive AES IV (16 raw bytes, length-prefixed LE)
     * 4. Decrypt AES key with RSA private key (RSA/ECB/PKCS1Padding)
     * 5. Receive encrypted EncAuthRequest (AES/CBC/PKCS5Padding)
     * 6. Decrypt with AES, parse JSON, verify PIN
     * 7. Send encrypted EncAuthResponse (AES/CBC/PKCS5Padding)
     * 8. Enter command loop (skips heartbeat frames)
     */
    private fun handleConnection(client: Socket) {
        var pairingSuccess = false
        try {
            client.use {
                // Set TCP keepalive + timeouts
                it.keepAlive = true
                it.soTimeout = 30000  // 30s timeout for handshake

                val input = DataInputStream(it.getInputStream())
                val output = DataOutputStream(it.getOutputStream())

                WepWawetLogger.proxy("Handling pairing connection",
                    "remote" to it.remoteSocketAddress.toString())

                // === PC → phone: Send RSA exponent FIRST (as 3-byte BE BigInteger) ===
                // VERIFIED from real capture: Frame 0 = length=3 + bytes `01 00 01` = 65537
                // (was modulus-first in earlier code — WRONG)
                val (modulus, exponent) = getRsaPublicKeyComponents()
                val expBytes = stripLeadingZero(exponent.toByteArray())
                WepWawetLogger.proxyDebug("Sending RSA exponent",
                    "exponent" to exponent.toString(16),
                    "wire_bytes" to expBytes.size,
                    "wire_hex" to expBytes.joinToString("") { "%02x".format(it) })
                writeLeInt(output, expBytes.size)
                output.write(expBytes)
                output.flush()

                // === PC → phone: Send RSA modulus (128 raw BE bytes for RSA-1024) ===
                // VERIFIED: 128 bytes, no sign byte (BigInteger.toByteArray may include
                // a leading 0x00 sign byte — strip it).
                val modBytes = stripLeadingZero(modulus.toByteArray())
                // Pad to exactly 128 bytes if needed (left-pad with zeros)
                val modPadded = if (modBytes.size < RSA_BLOCK_SIZE) {
                    ByteArray(RSA_BLOCK_SIZE - modBytes.size) + modBytes
                } else {
                    modBytes
                }
                WepWawetLogger.proxyDebug("Sending RSA modulus",
                    "modulus_bits" to modulus.bitLength(),
                    "wire_bytes" to modPadded.size,
                    "wire_hex_first_16" to modPadded.take(16).joinToString("") { "%02x".format(it) })
                writeLeInt(output, modPadded.size)
                output.write(modPadded)
                output.flush()

                // === Phone → PC: Receive 3 RSA-encrypted frames (128B each) ===
                // VERIFIED from real capture: phone sends 3 × 128B RSA-encrypted blocks
                //   Frame 0: RSA-encrypted AES key
                //   Frame 1: RSA-encrypted AES IV (16-byte IV padded to 128B RSA block)
                //   Frame 2: RSA-encrypted third block (purpose TBD — possibly MAC/session)
                // (earlier code expected only 1 RSA-encrypted frame + 16-byte raw IV — WRONG)
                val encAesKey = readLengthPrefixed(input, "RSA-encrypted AES key")
                    ?: return
                val encIv = readLengthPrefixed(input, "RSA-encrypted IV")
                    ?: return
                val encThird = readLengthPrefixed(input, "RSA-encrypted third block")
                    ?: return

                // Decrypt with RSA/ECB/PKCS1Padding
                val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair!!.private)
                val aesKeyBytes = rsaCipher.doFinal(encAesKey)
                val ivBytes = rsaCipher.doFinal(encIv)
                val thirdBlock = rsaCipher.doFinal(encThird)

                currentAesKey = SecretKeySpec(aesKeyBytes, "AES")
                currentIv = IvParameterSpec(ivBytes)

                WepWawetLogger.proxy("AES key exchange complete",
                    "aes_key_bits" to (aesKeyBytes.size * 8),
                    "iv_bytes" to ivBytes.size,
                    "third_block_bytes" to thirdBlock.size,
                    "third_block_hex_first_32" to thirdBlock.take(32)
                        .joinToString("") { "%02x".format(it) })

                // Step 4: Receive encrypted EncAuthRequest (length LITTLE-ENDIAN!)
                val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                aesCipher.init(Cipher.DECRYPT_MODE, currentAesKey, currentIv)

                val encAuthReqLen = readLeInt(input)
                WepWawetLogger.proxyDebug("Received encrypted EncAuthRequest",
                    "length" to encAuthReqLen)
                if (encAuthReqLen <= 0 || encAuthReqLen > MAX_SAFE_DATA_LENGTH) {
                    WepWawetLogger.proxyError("Invalid EncAuthRequest length: $encAuthReqLen")
                    return
                }
                val encAuthReqBytes = ByteArray(encAuthReqLen)
                input.readFully(encAuthReqBytes)

                val authReqJson = String(aesCipher.doFinal(encAuthReqBytes), Charsets.UTF_8)
                WepWawetLogger.proxy("Received EncAuthRequest",
                    "json" to authReqJson)

                // Step 5: Parse EncAuthRequest (JSON via Gson)
                // Expected fields: pin, name, deviceUserName, displayWidth, displayHeight,
                    // version, mpkgVersion, appVersion
                val gson = com.google.gson.Gson()
                val authReq = gson.fromJson(authReqJson, EncAuthRequest::class.java)

                WepWawetLogger.proxy("EncAuthRequest parsed",
                    "pin" to (authReq.pin ?: ""),
                    "name" to (authReq.name ?: ""),
                    "deviceUserName" to (authReq.deviceUserName ?: ""),
                    "displayWidth" to (authReq.displayWidth ?: 0),
                    "displayHeight" to (authReq.displayHeight ?: 0),
                    "version" to (authReq.version ?: 0),
                    "mpkgVersion" to (authReq.mpkgVersion ?: ""),
                    "appVersion" to (authReq.appVersion ?: ""))

                // Step 6: Verify PIN
                val pinValid = authReq.pin == currentPin
                WepWawetLogger.proxy("PIN verification",
                    "received_pin" to (authReq.pin ?: ""),
                    "expected_pin" to currentPin,
                    "valid" to pinValid)

                // Step 7: Send encrypted EncAuthResponse
                val authResponse = EncAuthResponse()
                authResponse.status = if (pinValid) "ok" else "invalid_pin"

                val respJson = gson.toJson(authResponse)
                WepWawetLogger.proxyDebug("Sending EncAuthResponse",
                    "json" to respJson,
                    "status" to authResponse.status)

                // Re-init cipher for encryption (AES/CBC is stateful)
                val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                encCipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
                val respBytes = encCipher.doFinal(respJson.toByteArray(Charsets.UTF_8))
                // LITTLE-ENDIAN length prefix!
                writeLeInt(output, respBytes.size)
                output.write(respBytes)
                output.flush()

                if (!pinValid) {
                    WepWawetLogger.proxyWarn("Pairing failed — invalid PIN")
                    onPairingFailure?.invoke("Invalid PIN")
                    return
                }

                // Step 8: Pairing successful!
                pairingSuccess = true
                currentClient = client
                it.soTimeout = 0  // Remove timeout for command loop

                val deviceName = authReq.deviceUserName ?: authReq.name ?: "Unknown"
                WepWawetLogger.proxy("Pairing successful!",
                    "device_name" to deviceName,
                    "app_version" to (authReq.appVersion ?: ""),
                    "mpkg_version" to (authReq.mpkgVersion ?: ""),
                    "display" to "${authReq.displayWidth}x${authReq.displayHeight}")
                onPairingSuccess?.invoke(deviceName)

                // Step 9: Enter command loop (handle subsequent commands)
                commandLoop(input, output)

            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Pairing connection error",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                exception = e)
            if (!pairingSuccess) {
                onPairingFailure?.invoke(e.message ?: "Connection error")
            }
        }
    }

    /**
     * Command loop — handles subsequent commands after pairing.
     * All messages are AES-encrypted with the established key.
     *
     * Wire format per message (LITTLE-ENDIAN length prefix — matches phone):
     *   writeLeInt(ciphertext_length)  (4 bytes little-endian)
     *   ciphertext                     (AES/CBC/PKCS5Padding encrypted JSON)
     *
     * Heartbeat: The phone sends "heartbeat\n" (10 ASCII bytes, no length prefix)
     * every ~3 seconds. We must detect and skip these frames.
     *
     * The phone may send commands like:
     *   - Request wallpaper download
     *   - Request playlist
     *   - Pause/stop wallpaper
     *   - etc.
     *
     * For now, we log every received command for the RE effort.
     */
    private fun commandLoop(input: DataInputStream, output: DataOutputStream) {
        WepWawetLogger.proxy("Entering command loop — waiting for commands from WE Android app")

        val decCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        decCipher.init(Cipher.DECRYPT_MODE, currentAesKey, currentIv)

        var heartbeatCount = 0
        var messageCount = 0
        while (running && currentClient?.isConnected == true) {
            try {
                val (kind, payload) = readMessageOrHeartbeat(input)
                when (kind) {
                    "heartbeat" -> {
                        heartbeatCount++
                        if (heartbeatCount % 10 == 1) {
                            WepWawetLogger.proxyDebug("Heartbeat received",
                                "count" to heartbeatCount)
                        }
                        continue
                    }
                    "invalid" -> {
                        WepWawetLogger.proxyWarn("Invalid message length in command loop")
                        break
                    }
                    else -> {
                        val msgBytes = payload ?: continue
                        messageCount++
                        val decrypted = decCipher.doFinal(msgBytes)
                        val commandJson = String(decrypted, Charsets.UTF_8)

                        WepWawetLogger.proxy("Received command",
                            "length" to msgBytes.size,
                            "msg_index" to messageCount,
                            "decrypted_json" to commandJson.take(500))

                        // TODO: Parse and handle specific commands
                        // For now, send a generic "ok" response (LITTLE-ENDIAN length!)
                        val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        encCipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
                        val respBytes = encCipher.doFinal("""{"status":"ok"}""".toByteArray(Charsets.UTF_8))
                        writeLeInt(output, respBytes.size)
                        output.write(respBytes)
                        output.flush()
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                // Normal — keep alive
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

/**
 * EncAuthRequest — the phone sends this encrypted with AES to authenticate.
 *
 * Fields from decompiled TcpClient$EncAuthRequest:
 *   pin: String              — 4-digit PIN shown on the "PC"
 *   name: String             — device name
 *   deviceUserName: String   — custom device name entered by user
 *   displayWidth: Int        — phone screen width
 *   displayHeight: Int       — phone screen height
 *   version: Int              — protocol version (default 4)
 *   mpkgVersion: String       — supported .mpkg version
 *   appVersion: String        — WE Android app version
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
 * EncAuthResponse — the "PC" sends this encrypted with AES.
 *
 * Fields from decompiled TcpClient$EncAuthResponse:
 *   status: String — "ok" for success, anything else for failure
 */
data class EncAuthResponse(
    var status: String = "",
)
