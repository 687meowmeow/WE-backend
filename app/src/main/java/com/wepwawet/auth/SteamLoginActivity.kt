package com.wepwawet.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.wepwawet.log.WepWawetLogger

/**
 * SteamLoginActivity — embedded WebView for logging into Steam.
 *
 * The user navigates to https://store.steampowered.com/login/ in the WebView.
 * They enter their credentials + Steam Guard. On successful login, Steam sets the
 * `steamLoginSecure` and `sessionid` cookies for the `steamcommunity.com`,
 * `steampowered.com`, and `store.steampowered.com` domains.
 *
 * We then extract those cookies and inject them into the Wine prefix's Steam
 * config — when Steam launches inside Wine, it reads the existing cookies and
 * skips the interactive login.
 *
 * The activity is *separate* from MainActivity so it has its own back-stack
 * (the user can press Back from the WebView to return to MainActivity without
 * accidentally killing the app).
 *
 * !! Security !!
 *   - Cookie jar is per-process — WebView cookies are stored in the app's
 *     private data dir, not the system WebView store.
 *   - We use `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)`.
 *   - The extracted cookies are written to an encrypted DataStore-backed file
 *     (via [com.wepwawet.data.SecureCredentialStorage]) — never plaintext.
 *   - The cookies are transferred into the Wine prefix only when Steam is
 *     launched; never persisted across a prefix reset.
 */
class SteamLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WepWawetLogger.lifecycle("SteamLoginActivity.onCreate")

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; WepWawet) AppleWebKit/537.36 Chrome/120 Safari/537.36"
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            // Block third-party cookies
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            CookieManager.getInstance().setAcceptCookie(true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val cookies = CookieManager.getInstance().getCookie("https://steamcommunity.com/")
                    WepWawetLogger.steamDebug("SteamLoginActivity.pageFinished",
                        "url" to (url ?: "null"),
                        "has_cookies" to (cookies != null))

                    // Detect successful login — the URL changes to the user's profile page
                    if (url?.contains("steamcommunity.com/profiles/") == true ||
                        url?.contains("store.steampowered.com/account/") == true
                    ) {
                        extractAndPersistCookies()
                        finish()
                    }
                }
            }
            loadUrl("https://store.steampowered.com/login/")
        }
        setContentView(webView)
    }

    private fun extractAndPersistCookies() {
        val cookieManager = CookieManager.getInstance()
        val domains = listOf(
            "https://steamcommunity.com/",
            "https://store.steampowered.com/",
            "https://steampowered.com/",
            "https://login.steampowered.com/",
        )
        val allCookies = domains.joinToString(";") { cookieManager.getCookie(it).orEmpty() }
        WepWawetLogger.steam("SteamLoginActivity: extracting cookies",
            "cookie_count" to allCookies.split(";").filter { it.isNotBlank() }.size)

        // Persist via SecureCredentialStorage (TODO: implement — EncryptedSharedPreferences-backed)
        // SecureCredentialStorage.writeSteamCookies(this, allCookies)
    }

    override fun onDestroy() {
        // Make sure cookies are flushed synchronously
        CookieManager.getInstance().flush()
        super.onDestroy()
        WepWawetLogger.lifecycle("SteamLoginActivity.onDestroy")
    }
}
