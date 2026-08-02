package com.lightshield.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Cookie helpers for YouTube / Google login in WebView.
 *
 * Do NOT manually re-inject cookies from SharedPreferences — that strips
 * Secure/SameSite/HttpOnly metadata and triggers Google's
 * "problem with your cookie settings" interstitial.
 */
object LightCookieManager {
    private const val TAG = "LightCookieManager"
    private const val PREFS = "otube_cookie_store"
    private const val PREFS_META = "otube_cookie_meta"
    private const val KEY_CLEANED_V2 = "cleaned_corrupt_backup_v2"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun configure(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        webView.settings.userAgentString = chromeMobileUserAgent(webView.context)
    }

    /**
     * Chrome mobile UA without WebView fingerprints (`wv`, `Version/4.0`),
     * so Google is more likely to set normal long-lived cookies.
     */
    fun chromeMobileUserAgent(context: Context): String {
        val raw = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Throwable) {
            System.getProperty("http.agent").orEmpty()
        }
        return raw
            .replace("; wv)", ")")
            .replace("; wv", "")
            .replace(" wv)", ")")
            .replace(" Version/4.0", "")
            .trim()
    }

    /**
     * One-time cleanup: older builds wrote broken cookie backups that poison
     * Google's login. Clear that prefs file and wipe the WebView cookie jar once.
     */
    fun migrateCorruptBackupIfNeeded(context: Context) {
        val app = context.applicationContext
        val meta = app.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE)
        if (meta.getBoolean(KEY_CLEANED_V2, false)) return

        try {
            val legacy = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hadBackup = legacy.all.keys.any { it.startsWith("cookies_") }
            legacy.edit().clear().commit()

            if (hadBackup) {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                Log.i(TAG, "Cleared corrupt cookie backup + WebView cookies")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "migrateCorruptBackupIfNeeded failed", t)
        }

        meta.edit().putBoolean(KEY_CLEANED_V2, true).commit()
    }

    fun flush() {
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed", t)
        }
    }

    fun flushAsync() {
        mainHandler.post { flush() }
    }
}
