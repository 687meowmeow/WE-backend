package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.log.WepWawetLogger

/**
 * Stores Steam credentials for JavaSteam workshop downloads.
 *
 * The username and password are stored in SharedPreferences (MODE_PRIVATE,
 * app-sandboxed). They are used by [WorkshopDownloader] to authenticate
 * with Steam's binary TCP protocol (same as SteamCMD).
 *
 * For production: should use EncryptedSharedPreferences (already in the
 * dependency tree via androidx.security.crypto). For alpha, plain
 * MODE_PRIVATE is acceptable.
 */
object SteamCredentialsStore {
    private const val PREF_NAME = "wepwawet_steam_credentials"
    private const val KEY_USERNAME = "steam_username"
    private const val KEY_PASSWORD = "steam_password"
    private const val KEY_GUARD_CODE = "steam_guard_code"

    fun saveCredentials(context: Context, username: String, password: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
        WepWawetLogger.proxy("SteamCredentialsStore: credentials saved",
            "username" to username)
    }

    fun saveGuardCode(context: Context, code: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GUARD_CODE, code).apply()
        WepWawetLogger.proxy("SteamCredentialsStore: guard code saved")
    }

    fun getGuardCode(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GUARD_CODE, null)?.takeIf { it.isNotBlank() }
    }

    fun getUsername(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, null)
    }

    fun getPassword(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PASSWORD, null)
    }

    fun hasCredentials(context: Context): Boolean {
        val username = getUsername(context)
        val password = getPassword(context)
        return !username.isNullOrBlank() && !password.isNullOrBlank()
    }

    fun clearCredentials(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        WepWawetLogger.proxy("SteamCredentialsStore: credentials cleared")
    }
}
