package com.wepwawet.proxy

import android.content.Context
import android.net.wifi.WifiManager
import com.wepwawet.log.WepWawetLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * WeDiscoveryServer — UDP 7884 listener that replies to the official WE Android app's
 * discovery broadcasts.
 *
 * Per research Task 1-B:
 *   - WE PC client (the `ui32.exe` process) broadcasts UDP to:
 *     1. The subnet broadcast (e.g. 192.168.0.255:7884)
 *     2. The multicast group 239.100.0.1:7884
 *   - Payload format: `WallpaperEngine/Ping:NNNNN:HOSTNAME:N:INTERFACEID`
 *
 * The Android WE app's role: send a broadcast and listen for `WallpaperEngine/Ping` replies
 * (the Android app is the CLIENT, the PC is the BROADCASTER).
 *
 * So our DiscoveryServer's job is the OPPOSITE of what the research said:
 *   - **Our server acts as the PC** — it broadcasts `WallpaperEngine/Ping` to advertise itself
 *   - The Android WE app listens for these pings
 *   - When the Android app sees a ping, it knows a PC is available
 *   - User taps the discovered PC → enters PIN → TCP 7889 handshake
 *
 * We broadcast on:
 *   - 255.255.255.255:7884 (subnet broadcast) — for the LAN-pair fallback
 *   - 239.100.0.1:7884 (multicast) — for clients listening on the multicast group
 *   - 127.0.0.1:7884 (loopback) — for self-pair (the official WE Android app, running on
 *     the same device, picks up the ping via loopback)
 *
 * Multicast caveat (research Task 1-D — inconclusive):
 *   Whether Android propagates multicast between apps on the same device is uncertain.
 *   The manual-IP fallback (user enters 127.0.0.1 in the official WE Android app)
 *   is the primary path. The broadcast is best-effort.
 *
 * We also LISTEN for any incoming UDP packets on 7884 — the WE Android app may send
 * a "Pong" reply back. The reply format is undocumented (needs jadx RE) — for now we
 * just log it.
 */
class WeDiscoveryServer(
    private val context: Context,
) {

    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null
    @Volatile private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) {
            WepWawetLogger.proxyWarn("WeDiscoveryServer.start called but already running")
            return
        }
        running = true

        // Acquire multicast lock so the kernel doesn't drop inbound multicast packets
        // when the screen is off. Only the multicast group needs this — broadcasts work
        // without it.
        acquireMulticastLock()

        broadcastThread = thread(start = true, name = "wep-discovery-bcast", isDaemon = true) {
            broadcastLoop()
        }
        listenThread = thread(start = true, name = "wep-discovery-listen", isDaemon = true) {
            listenLoop()
        }
        WepWawetLogger.proxy("WeDiscoveryServer started")
    }

    fun stop() {
        running = false
        runCatching { broadcastThread?.interrupt() }
        runCatching { listenThread?.interrupt() }
        releaseMulticastLock()
        WepWawetLogger.proxy("WeDiscoveryServer stopped")
    }

    // ====== Broadcast loop ======

    private fun broadcastLoop() {
        val pid = android.os.Process.myPid().toString()
        val hostname = "wepwawet-${System.currentTimeMillis().toString().takeLast(4)}"
        val payload = "WallpaperEngine/Ping:$pid:$hostname:1:lo"
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        val intervalMs = 5000L  // WE broadcasts every 1-2s, we can be slower

        while (running) {
            try {
                // Broadcast to subnet broadcast address (255.255.255.255 — let the kernel route)
                sendUdp(InetAddress.getByName("255.255.255.255"), PORT, bytes)

                // Multicast to 239.100.0.1 — WE's specific multicast group
                sendMulticast(InetAddress.getByName(MULTICAST_GROUP), PORT, bytes)

                // Loopback for self-pair (the official WE Android app may not see this,
                // but if it does, great)
                sendUdp(InetAddress.getByName("127.0.0.1"), PORT, bytes)

                WepWawetLogger.proxyDebug("broadcast",
                    "pid" to pid, "hostname" to hostname, "interval_ms" to intervalMs)
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("broadcast iteration failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            }
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
        }
    }

    // ====== Listen loop ======

    private fun listenLoop() {
        try {
            val socket = java.net.DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            socket.soTimeout = 1500  // wake up periodically to check `running`
            val buf = ByteArray(2048)
            WepWawetLogger.proxy("WeDiscoveryServer listening on UDP $PORT")

            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val payload = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val src = packet.address.hostAddress ?: "?"
                    val srcPort = packet.port
                    WepWawetLogger.proxyDebug("received UDP",
                        "src" to src, "port" to srcPort, "payload" to payload)
                    handleIncoming(src, srcPort, payload)
                } catch (_: java.net.SocketTimeoutException) {
                    // normal — loop back and check `running`
                } catch (e: Exception) {
                    if (running) WepWawetLogger.proxyWarn("listen iteration error",
                        "exception" to e.javaClass.name)
                }
            }
            socket.close()
        } catch (e: SocketException) {
            if (running) WepWawetLogger.proxyError("listen loop SocketException", exception = e)
        }
    }

    private fun handleIncoming(src: String, srcPort: Int, payload: String) {
        // We're acting as the PC — we shouldn't be receiving "Ping" packets from a phone,
        // but if we get a Pong or other reply, log it for the RE effort
        when {
            payload.startsWith("WallpaperEngine/Pong") -> {
                WepWawetLogger.proxyDebug("received Pong", "src" to src, "payload" to payload)
                // TODO (RE pending): handle the Pong payload, store the discovered device
            }
            payload.startsWith("WallpaperEngine/Ping") -> {
                // A different PC advertised itself — we don't care, ignore.
            }
            else -> {
                WepWawetLogger.proxyDebug("received unknown UDP payload",
                    "src" to src, "port" to srcPort, "len" to payload.length)
            }
        }
    }

    // ====== Socket helpers ======

    private fun sendUdp(address: InetAddress, port: Int, bytes: ByteArray) {
        try {
            val socket = java.net.DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }
            socket.use {
                val pkt = DatagramPacket(bytes, bytes.size, address, port)
                it.send(pkt)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("sendUdp failed",
                "addr" to address.hostAddress, "port" to port, "exception" to e.javaClass.name)
        }
    }

    private fun sendMulticast(group: InetAddress, port: Int, bytes: ByteArray) {
        try {
            // MulticastSocket.joinGroup is deprecated in API 33+ but works fine
            @Suppress("DEPRECATION")
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                timeToLive = 255  // local subnet
            }
            socket.use {
                @Suppress("DEPRECATION")
                it.joinGroup(group)
                val pkt = DatagramPacket(bytes, bytes.size, group, port)
                it.send(pkt)
                @Suppress("DEPRECATION")
                it.leaveGroup(group)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("sendMulticast failed",
                "group" to group.hostAddress, "port" to port, "exception" to e.javaClass.name)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("wepwawet:discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            WepWawetLogger.proxyDebug("multicast lock acquired")
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("multicast lock acquire failed",
                "exception" to e.javaClass.name)
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        multicastLock = null
    }

    companion object {
        const val PORT = 7884
        const val MULTICAST_GROUP = "239.100.0.1"
    }
}
