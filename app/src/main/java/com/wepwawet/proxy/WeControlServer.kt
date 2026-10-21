package com.wepwawet.proxy

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * WeControlServer — TCP 7889 listener that implements the WE pairing handshake + control.
 *
 * Per research Task 1-B:
 *   - WE Android app connects to PC:7889 (manual IP entry fallback for self-pair)
 *   - 4-digit PIN auth (entered by user from PC UI into Android app)
 *   - PIN-derived symmetric key (algorithm TBD pending RE — we hypothesize
 *     PBKDF2-HMAC-SHA256 with the PIN as the password, a fixed salt, and AES-256-GCM
 *     for the wire protocol; this is the most common pattern for PIN-based protocols)
 *   - Persistent pairing: PC remembers the device after the first successful PIN exchange
 *   - Wallpaper transfer: PC pushes `.mpkg` files over the same TCP channel
 *   - Live control: same TCP channel — "switch wallpaper", "pause", "next"
 *
 * What's implemented here (v0.1):
 *   - TCP server accepts connections on 0.0.0.0:7889 (Android sandbox allows this)
 *   - Reads first N bytes to identify the message type (handshake vs control vs file push)
 *   - Handshake validation against the current PIN (basic — real protocol TBD)
 *   - Pairing token storage via [PairedDeviceStore]
 *   - Skeleton for control command dispatch (real wire format pending RE)
 *
 * What's NOT implemented:
 *   - The exact handshake wire format (need Wireshark capture + jadx decompile of
 *     io.wallpaperengine.weclient)
 *   - The PIN-derived key derivation algorithm (need jadx RE)
 *   - The exact message framing for control commands and file transfer
 *
 * Until RE confirms, this server is a RESEARCH INSTRUMENT — it logs every byte it
 * sees, which is invaluable for capturing the wire format when a real WE Android app
 * connects to it.
 */
class WeControlServer(
    private val context: Context,
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val connections = ConcurrentHashMap<String, ConnectionHandler>()
    private val pairedDevices = PairedDeviceStore(context)
    private val crypto = PairingCrypto()

    fun start() {
        if (running) {
            WepWawetLogger.proxyWarn("WeControlServer.start called but already running")
            return
        }
        running = true
        serverSocket = ServerSocket().apply {
            // Bind 0.0.0.0:7889 so the WE Android app can connect to 127.0.0.1:7889
            // or to the device's LAN IP for the LAN-pair fallback
            bind(InetSocketAddress(PORT))
            soTimeout = 1500  // wake up periodically
        }
        thread(start = true, name = "wep-control-listen", isDaemon = true) {
            acceptLoop()
        }
        WepWawetLogger.proxy("WeControlServer listening on TCP $PORT")
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
                if (running) WepWawetLogger.proxyWarn("accept error",
                    "exception" to e.javaClass.name)
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

        fun run() {
            try {
                socket.use {
                    val input = DataInputStream(BufferedInputStream(it.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(it.getOutputStream()))

                    WepWawetLogger.proxy("connection accepted", "remote" to remoteAddr)

                    while (running && !socket.isClosed) {
                        val firstByte = try {
                            input.read()
                        } catch (e: java.net.SocketException) {
                            break
                        }
                        if (firstByte == -1) break
                        handleMessage(firstByte, input, output)
                    }
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("connection handler ended",
                    "remote" to remoteAddr, "exception" to e.javaClass.name)
            } finally {
                connections.remove(remoteAddr)
            }
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun handleMessage(firstByte: Int, input: DataInputStream, output: DataOutputStream) {
            // === RE EFFORT ===
            // Until we have the actual wire format from Wireshark/jadx, we log raw bytes.
            // Once we know the protocol, we'll implement message dispatch here.

            // Read up to 64KB of any remaining buffered bytes for analysis
            val available = input.available().coerceAtMost(64 * 1024)
            val rest = ByteArray(available).also { input.readFully(it) }
            val rawHex = rest.joinToString("") { "%02x".format(it) }
            val rawAscii = rest.joinToString("") {
                if (it in 32..126) it.toInt().toChar().toString() else "."
            }

            WepWawetLogger.proxy("received bytes",
                "remote" to remoteAddr,
                "first_byte" to "0x%02x".format(firstByte),
                "rest_len" to rest.size,
                "rest_hex_first_64" to rawHex.take(64),
                "rest_ascii_first_64" to rawAscii.take(64),
            )

            // ==== Skeleton protocol ====
            // TODO(RE): once we have the wire format, dispatch on firstByte:
            //   firstByte == 0x00 -> pairing handshake (validate PIN)
            //   firstByte == 0x01 -> control command (switch wallpaper, etc.)
            //   firstByte == 0x02 -> request wallpaper push (server will send .mpkg)
            //   firstByte == 0x03 -> heartbeat/ping
            //   ...

            // For now: log and reply with empty response to keep the connection alive
            // so we can capture more of the protocol.
            // DO NOT do this in v1 — the real protocol likely expects a specific reply
            // or it will time out.
            try {
                output.write(byteArrayOf(0x00))  // placeholder ack
                output.flush()
            } catch (_: Exception) { /* connection lost */ }
        }
    }

    /**
     * Push a .mpkg file to a specific paired device (or all if deviceId is null).
     * Called by [MpkgTransporter].
     */
    fun pushMpkgToDevice(mpkgFile: File, deviceId: String?) {
        val target = deviceId?.let { listOf(it) } ?: connections.keys.toList()
        for (id in target) {
            val handler = connections[id] ?: continue
            // TODO(RE): implement the actual push wire format
            // For now: log intent
            WepWawetLogger.proxyDebug("pushMpkgToDevice (stub)",
                "device" to id, "file" to mpkgFile.name, "file_size" to mpkgFile.length())
        }
    }

    companion object {
        const val PORT = 7889
        const val PAIRING_PIN_LENGTH = 4
    }
}
