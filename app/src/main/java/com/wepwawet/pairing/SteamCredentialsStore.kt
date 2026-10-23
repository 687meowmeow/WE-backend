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
        // Clear any saved refresh token when the user re-enters credentials.
        // Why: Steam made a backend change in 2025 where refresh tokens for the
        // SteamClient platform are now "authenticated" — meaning previously-valid
        // tokens can suddenly start returning AccessDenied. When the user re-enters
        // their credentials, they almost certainly want a FRESH login (push
        // notification), not a stale-token attempt that will fail.
        // The refresh token is stored in a separate SharedPreferences file
        // ("wepwawet_steam_token") by WorkshopDownloader, so we clear that too.
        val tokenPrefs = context.getSharedPreferences("wepwawet_steam_token", Context.MODE_PRIVATE)
        val hadToken = tokenPrefs.getString("refresh_token", null) != null
        val hadAccountName = tokenPrefs.getString("account_name", null) != null
        if (hadToken || hadAccountName) {
            tokenPrefs.edit().clear().apply()
            WepWawetLogger.proxy("SteamCredentialsStore: cleared stale refresh token (user re-saved credentials)",
                "had_token" to hadToken,
                "had_account_name" to hadAccountName)
        }
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
