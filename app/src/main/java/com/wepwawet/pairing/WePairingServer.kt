package com.wepwawet.pairing

import android.util.Log
import com.wepwawet.log.WepWawetLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
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
 *   writeInt(ciphertext_length)   // 4 bytes, big-endian
 *   write(ciphertext)             // AES/CBC/PKCS5Padding encrypted JSON
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
        const val MAX_SAFE_DATA_LENGTH = 1048592  // 1 MB

        // RSA key size — 2048 is standard, fast enough on phone
        private const val RSA_KEY_SIZE = 2048

        // AES — 256-bit key, 128-bit IV (16 bytes)
        private const val AES_KEY_SIZE = 256
        private const val AES_IV_SIZE = 16
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
     * Handle a single pairing connection from the WE Android app.
     *
     * This implements the full pairing handshake:
     * 1. Send RSA public key
     * 2. Receive encrypted AES key
     * 3. Decrypt AES key with RSA private key
     * 4. Receive encrypted EncAuthRequest
     * 5. Decrypt with AES
     * 6. Verify PIN
     * 7. Send encrypted EncAuthResponse
     * 8. Enter command loop
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

                // Step 1: Send RSA public key
                val (modulus, exponent) = getRsaPublicKeyComponents()
                WepWawetLogger.proxyDebug("Sending RSA public key",
                    "modulus_bytes" to modulus.toByteArray().size,
                    "exponent_bytes" to exponent.toByteArray().size)

                // Send modulus: writeInt(length) + bytes
                val modBytes = modulus.toByteArray()
                output.writeInt(modBytes.size)
                output.write(modBytes)
                output.flush()

                // Send exponent: writeInt(length) + bytes
                val expBytes = exponent.toByteArray()
                output.writeInt(expBytes.size)
                output.write(expBytes)
                output.flush()

                WepWawetLogger.proxyDebug("RSA public key sent",
                    "modulus_hex_prefix" to modBytes.take(8).joinToString("") { "%02x".format(it) },
                    "exponent_hex" to expBytes.joinToString("") { "%02x".format(it) })

                // Step 2: Receive encrypted AES key (encrypted with our RSA public key)
                val encAesKeyLen = input.readInt()
                WepWawetLogger.proxyDebug("Received encrypted AES key",
                    "length" to encAesKeyLen)
                if (encAesKeyLen <= 0 || encAesKeyLen > 4096) {
                    WepWawetLogger.proxyError("Invalid AES key length: $encAesKeyLen")
                    return
                }
                val encAesKey = ByteArray(encAesKeyLen)
                input.readFully(encAesKey)

                // Step 3: Decrypt AES key with RSA private key
                val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair!!.private)
                val aesKeyBytes = rsaCipher.doFinal(encAesKey)

                // Also receive IV (16 bytes)
                val ivLen = input.readInt()
                if (ivLen != AES_IV_SIZE) {
                    WepWawetLogger.proxyError("Invalid IV length: $ivLen (expected $AES_IV_SIZE)")
                    return
                }
                val ivBytes = ByteArray(ivLen)
                input.readFully(ivBytes)

                currentAesKey = SecretKeySpec(aesKeyBytes, "AES")
                currentIv = IvParameterSpec(ivBytes)

                WepWawetLogger.proxy("AES key exchange complete",
                    "aes_key_bits" to (aesKeyBytes.size * 8),
                    "iv_bytes" to ivBytes.size)

                // Step 4: Receive encrypted EncAuthRequest
                val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                aesCipher.init(Cipher.DECRYPT_MODE, currentAesKey, currentIv)

                val encAuthReqLen = input.readInt()
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
                output.writeInt(respBytes.size)
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
     * Wire format per message:
     *   writeInt(ciphertext_length)  (4 bytes big-endian)
     *   ciphertext                   (AES/CBC/PKCS5Padding encrypted JSON)
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

        while (running && currentClient?.isConnected == true) {
            try {
                val msgLen = input.readInt()
                if (msgLen <= 0 || msgLen > MAX_SAFE_DATA_LENGTH) {
                    WepWawetLogger.proxyWarn("Invalid message length in command loop: $msgLen")
                    break
                }
                val msgBytes = ByteArray(msgLen)
                input.readFully(msgBytes)

                val decrypted = decCipher.doFinal(msgBytes)
                val commandJson = String(decrypted, Charsets.UTF_8)

                WepWawetLogger.proxy("Received command",
                    "length" to msgLen,
                    "decrypted_json" to commandJson.take(500))

                // TODO: Parse and handle specific commands
                // For now, send a generic "ok" response
                val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                encCipher.init(Cipher.ENCRYPT_MODE, currentAesKey, currentIv)
                val respBytes = encCipher.doFinal("""{"status":"ok"}""".toByteArray(Charsets.UTF_8))
                output.writeInt(respBytes.size)
                output.write(respBytes)
                output.flush()

            } catch (e: java.net.SocketTimeoutException) {
                // Normal — keep alive
            } catch (e: Exception) {
                if (running) {
                    WepWawetLogger.proxyWarn("Command loop ended",
                        "exception" to e.javaClass.name)
                }
                break
            }
        }
        WepWawetLogger.proxy("Command loop ended")
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
