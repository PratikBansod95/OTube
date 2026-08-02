package com.lightshield.utils

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Cookie helpers for keeping YouTube / Google login sessions across process deaths.
 */
object LightCookieManager {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun configureForPrivacy(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Google sign-in for YouTube needs cross-site cookies (accounts.google.com ↔ youtube.com).
        // Keeping these disabled is why logins vanish after the app process is killed.
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    /** Persist in-memory cookies to disk. Safe to call often. */
    fun flush() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
    }

    fun flushAsync() {
        mainHandler.post { flush() }
    }
}
