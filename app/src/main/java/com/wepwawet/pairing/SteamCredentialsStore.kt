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
        val oldUsername = prefs.getString(KEY_USERNAME, null)
        val usernameChanged = oldUsername != null && oldUsername != username
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
        // Only clear the saved refresh token if the USERNAME CHANGED.
        // Previously: we cleared the token on EVERY save, which meant re-saving
        // the same credentials (e.g., user pressed Save again by accident, or
        // the Settings screen was re-composed) would wipe the token and force
        // a full Steam Guard push-notification re-auth on the next download.
        // The user reported "the steam login only lasts one wallpaper then it
        // access denies me" — this was caused by the token being cleared
        // between downloads.
        //
        // Now: only clear the token if the user changed their username (which
        // genuinely invalidates the old token). Re-saving the same username
        // just updates the password without touching the token.
        val tokenPrefs = context.getSharedPreferences("wepwawet_steam_token", Context.MODE_PRIVATE)
        val hadToken = tokenPrefs.getString("refresh_token", null) != null
        val hadAccountName = tokenPrefs.getString("account_name", null) != null
        if (usernameChanged && (hadToken || hadAccountName)) {
            tokenPrefs.edit().clear().apply()
            WepWawetLogger.proxy("SteamCredentialsStore: cleared refresh token (username changed from '$oldUsername' to '$username')",
                "had_token" to hadToken,
                "had_account_name" to hadAccountName,
                "old_username" to (oldUsername ?: "null"),
                "new_username" to username)
        } else if (hadToken) {
            WepWawetLogger.proxy("SteamCredentialsStore: refresh token PRESERVED (username unchanged — only password updated)",
                "had_token" to hadToken,
                "username" to username)
        }
        WepWawetLogger.proxy("SteamCredentialsStore: credentials saved",
            "username" to username,
            "username_changed" to usernameChanged)
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
        // Also clear the saved refresh token + account name (they're stored
        // in a separate SharedPreferences file by WorkshopDownloader).
        context.getSharedPreferences("wepwawet_steam_token", Context.MODE_PRIVATE)
            .edit().clear().apply()
        WepWawetLogger.proxy("SteamCredentialsStore: credentials cleared (incl. refresh token)")
    }

    /**
     * Check if a Steam refresh token is saved (for password-less re-login
     * without Steam Guard push notification).
     *
     * Used by [SettingsScreen] to show the user whether their login is
     * "persistent" (will work on app restart) or "ephemeral" (needs Steam
     * Guard approval again). The token is saved by [WorkshopDownloader.onConnected]
     * after a successful modern-auth flow (with Steam Guard approval).
     *
     * v0.4.7: The token is kept valid across logins by setting
     * `details.shouldRememberPassword = true` on LogOnDetails — without that
     * flag, Steam invalidated the token after one use, causing the user's
     * "steam login only lasts one wallpaper then access denies me" bug.
     */
    fun hasRefreshToken(context: Context): Boolean {
        return context.getSharedPreferences("wepwawet_steam_token", Context.MODE_PRIVATE)
            .getString("refresh_token", null)?.isNotBlank() == true
    }

    /**
     * Get the saved Steam account name (canonical case from Steam backend).
     * Returns null if no refresh token has been saved yet.
     */
    fun getSavedAccountName(context: Context): String? {
        return context.getSharedPreferences("wepwawet_steam_token", Context.MODE_PRIVATE)
            .getString("account_name", null)?.takeIf { it.isNotBlank() }
    }
}
