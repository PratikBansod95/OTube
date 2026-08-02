package com.lightshield.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Cookie helpers for keeping YouTube / Google login across process deaths.
 *
 * Google often issues *session* cookies when it detects a WebView UA (`wv`).
 * Session cookies are discarded when the WebView "session" ends (app kill).
 * We (1) spoof a normal Chrome mobile UA and (2) mirror cookie jars to prefs.
 */
object LightCookieManager {
    private const val TAG = "LightCookieManager"
    private const val PREFS = "otube_cookie_store"
    private const val KEY_PREFIX = "cookies_"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val PERSIST_URLS = listOf(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com",
        "https://accounts.google.com",
        "https://accounts.youtube.com",
        "https://www.google.com",
        "https://google.com"
    )

    fun configure(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        webView.settings.userAgentString = chromeMobileUserAgent(webView.context)
    }

    /**
     * Chrome mobile UA without WebView fingerprints (`wv`, `Version/4.0`),
     * so Google is more likely to set long-lived cookies.
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

    /** Snapshot CookieManager → SharedPreferences (survives process death). */
    fun persistCookies(context: Context) {
        try {
            val cm = CookieManager.getInstance()
            cm.flush()
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (url in PERSIST_URLS) {
                val value = cm.getCookie(url)
                if (value.isNullOrBlank()) {
                    editor.remove(KEY_PREFIX + url)
                } else {
                    editor.putString(KEY_PREFIX + url, value)
                }
            }
            editor.commit() // synchronous — must finish before process death
        } catch (t: Throwable) {
            Log.w(TAG, "persistCookies failed", t)
        }
    }

    fun persistCookiesAsync(context: Context) {
        Thread {
            persistCookies(context)
        }.start()
    }

    /** SharedPreferences → CookieManager, then flush. Call before first loadUrl. */
    fun restorePersistedCookies(context: Context) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var restored = 0
            for (url in PERSIST_URLS) {
                val raw = prefs.getString(KEY_PREFIX + url, null) ?: continue
                // setCookie expects one "name=value; attrs" at a time.
                raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { piece ->
                    try {
                        cm.setCookie(url, piece)
                        restored++
                    } catch (_: Throwable) {
                    }
                }
            }
            cm.flush()
            Log.i(TAG, "Restored $restored cookie fragments")
        } catch (t: Throwable) {
            Log.w(TAG, "restorePersistedCookies failed", t)
        }
    }
}
