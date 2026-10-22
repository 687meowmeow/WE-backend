package com.wepwawet.proxy

import android.content.Context
import androidx.core.content.edit
import com.wepwawet.log.WepWawetLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent store of paired devices.
 *
 * Per research Task 1-B, the WE pairing is persistent: PC remembers the device after
 * the first successful PIN exchange. The official WE Android app maintains its own
 * list of paired PCs (one entry per PIN-validated session). We mirror that on our side.
 *
 * Storage format: SharedPreferences `wepwawet_paired_devices` containing a JSON array of
 * PairedDevice entries.
 *
 * NOTE: pairing tokens are stored unencrypted in v0.1 — for v1 we should encrypt them
 * with Android Keystore (AliasKey / AEAD). The token is a per-device shared secret; if
 * leaked, an attacker could impersonate the paired device. Risk is acceptable for a
 * single-user dev tool, but should be tightened before any wider release.
 */
class PairedDeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<PairedDevice> {
        val json = prefs.getString(KEY_DEVICES, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PairedDevice(
                id = o.getString("id"),
                name = o.optString("name", "Unknown"),
                pairedAt = o.getLong("pairedAt"),
                lastSeenAt = o.getLong("lastSeenAt"),
                pairingTokenHex = o.getString("pairingTokenHex"),
                firstSeenRemoteAddr = o.optString("firstSeenRemoteAddr", ""),
            )
        }
    }

    fun add(device: PairedDevice) {
        val list = all().toMutableList()
        list.removeAll { it.id == device.id }
        list.add(device)
        prefs.edit { putString(KEY_DEVICES, listToJson(list)) }
        WepWawetLogger.proxy("PairedDeviceStore.add", "id" to device.id, "name" to device.name)
    }

    fun remove(id: String) {
        val list = all().filterNot { it.id == id }
        prefs.edit { putString(KEY_DEVICES, listToJson(list)) }
        WepWawetLogger.proxy("PairedDeviceStore.remove", "id" to id)
    }

    fun touch(id: String, remoteAddr: String?) {
        val list = all().map { d ->
            if (d.id == id) d.copy(lastSeenAt = System.currentTimeMillis(),
                firstSeenRemoteAddr = remoteAddr ?: d.firstSeenRemoteAddr) else d
        }
        prefs.edit { putString(KEY_DEVICES, listToJson(list)) }
    }

    fun clear() {
        prefs.edit { remove(KEY_DEVICES) }
    }

    private fun listToJson(list: List<PairedDevice>): String {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("pairedAt", d.pairedAt)
                put("lastSeenAt", d.lastSeenAt)
                put("pairingTokenHex", d.pairingTokenHex)
                put("firstSeenRemoteAddr", d.firstSeenRemoteAddr)
            })
        }
        return arr.toString()
    }

    companion object {
        const val PREFS_NAME = "wepwawet_paired_devices"
        const val KEY_DEVICES = "devices"

        fun newDeviceId(): String = UUID.randomUUID().toString()
    }
}

data class PairedDevice(
    val id: String,
    val name: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
    val pairingTokenHex: String,  // hex-encoded shared secret (post-pairing)
    val firstSeenRemoteAddr: String,
)
