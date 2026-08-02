package com.lightshield

import android.app.Application
import android.webkit.CookieManager
import com.lightshield.utils.LightCookieManager

class OTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize cookie store before any WebView so restores stick.
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            LightCookieManager.restorePersistedCookies(this)
        } catch (_: Throwable) {
        }
    }
}
