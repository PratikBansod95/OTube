package com.lightshield.utils

import android.webkit.CookieManager

object LightCookieManager {
    fun configureForPrivacy() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Third-party cookies are disabled per-WebView in LightWebView.
    }
}
