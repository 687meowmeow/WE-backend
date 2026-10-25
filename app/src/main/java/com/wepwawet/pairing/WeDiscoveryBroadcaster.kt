package com.wepwawet.pairing

import android.content.Context
import android.net.wifi.WifiManager
import com.wepwawet.log.WepWawetLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

/**
 * WE Discovery Broadcaster — broadcasts "WallpaperEngine/Ping" on UDP 7884.
 *
 * This makes our app discoverable by the official WE Android app's DiscoverService.
 * When the user opens "Pair with Computer" in the WE Android app, it listens for
 * these pings on UDP 7884 multicast group 239.100.0.1.
 *
 * Payload format (from decompiled DiscoverService.java):
 *   "WallpaperEngine/Ping:<pid>:<hostname>:<flag>:<interfaceId>"
 *
 * Fields:
 *   pid: Process ID (5 digits) — we use a random 5-digit number
 *   hostname: Device name — we use "WepWawet"
 *   flag: Usually "1"
 *   interfaceId: Network interface ID — we use "lo" (loopback) for self-pair
 *
 * The WE Android app parses this and creates a DeviceItem with:
 *   deviceName = hostname
 *   ipAddress = source IP of the UDP packet
 *   port = 7889 (implied — the TCP pairing port)
 */
class WeDiscoveryBroadcaster(private val context: Context) {

    companion object {
        const val UDP_PORT = 7884
        const val MULTICAST_GROUP = "239.100.0.1"
        private const val BROADCAST_INTERVAL_MS = 1300L  // VERIFIED from Wireshark: PC broadcasts every ~1.3s
    }

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    private var broadcastThread: Thread? = null

    fun start(hostname: String = "WepWawet") {
        if (running) {
            WepWawetLogger.proxyWarn("Discovery broadcaster already running")
            return
        }
        running = true
        WepWawetLogger.proxy("Discovery broadcaster starting",
            "hostname" to hostname,
            "udp_port" to UDP_PORT,
            "multicast_group" to MULTICAST_GROUP)

        broadcastThread = thread(start = true, name = "wep-discovery-bcast", isDaemon = true) {
            broadcastLoop(hostname)
        }
    }

    fun stop() {
        running = false
        broadcastThread?.interrupt()
        runCatching { multicastSocket?.close() }
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastSocket = null
        multicastLock = null
        WepWawetLogger.proxy("Discovery broadcaster stopped")
    }

    private fun broadcastLoop(hostname: String) {
        try {
            // Acquire multicast lock so the kernel doesn't drop multicast packets
            acquireMulticastLock()

            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(UDP_PORT))
                timeToLive = 255
            }
            multicastSocket = socket

            val group = InetAddress.getByName(MULTICAST_GROUP)
            // VERIFIED from decompiled DiscoverService.java:
            //   parts[1] = TCP port (5 chars, zero-padded) — phone does parseInt(parts[1])
            //   parts[3] = protocolVersion — phone checks protocolVersion == 4 (DiscoverDevicesActivity line 181)
            //   parts[4] = deviceGuid (UUID string)
            // Real PC sends: "WallpaperEngine/Ping:07889:LAPTOP-NAME:4:5FB2F0F0-..."
            // Our previous code sent ":1:" for protocolVersion → phone showed "different version" error!
            val guid = java.util.UUID.randomUUID().toString().uppercase()
            val payload = "WallpaperEngine/Ping:07889:$hostname:4:$guid"
            val bytes = payload.toByteArray(Charsets.US_ASCII)

            WepWawetLogger.proxy("Discovery broadcaster listening on UDP $UDP_PORT",
                "payload" to payload)

            // Also listen for incoming UDP (the phone may send a Pong)
            socket.soTimeout = 1000  // wake up every 1s to check running

            while (running) {
                try {
                    // Broadcast to multicast group
                    val pkt = DatagramPacket(bytes, bytes.size, group, UDP_PORT)
                    socket.send(pkt)

                    // Also broadcast to 255.255.255.255 (subnet broadcast — for LAN pair)
                    val bcastAddr = InetAddress.getByName("255.255.255.255")
                    val bcastPkt = DatagramPacket(bytes, bytes.size, bcastAddr, UDP_PORT)
                    socket.send(bcastPkt)

                    // Also send to 127.0.0.1 (loopback — for self-pair)
                    val loopPkt = DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), UDP_PORT)
                    socket.send(loopPkt)

                    WepWawetLogger.proxyDebug("broadcast sent",
                        "payload" to payload)
                } catch (e: Exception) {
                    if (running) {
                        WepWawetLogger.proxyWarn("broadcast iteration failed",
                            "exception" to e.javaClass.name)
                    }
                }

                // Listen for incoming UDP (phone's Pong or other discovery replies)
                try {
                    val rxPkt = DatagramPacket(ByteArray(256), 256)
                    socket.receive(rxPkt)
                    val rxPayload = String(rxPkt.data, 0, rxPkt.length, Charsets.US_ASCII)
                    val src = rxPkt.address.hostAddress ?: "?"
                    WepWawetLogger.proxyDebug("received UDP reply",
                        "src" to src,
                        "port" to rxPkt.port,
                        "payload" to rxPayload)
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal — no reply, keep broadcasting
                }

                Thread.sleep(BROADCAST_INTERVAL_MS)
            }

            socket.close()
        } catch (e: Exception) {
            if (running) {
                WepWawetLogger.proxyError("Discovery broadcaster error",
                    "exception" to e.javaClass.name, exception = e)
            }
        } finally {
            runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
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
}
