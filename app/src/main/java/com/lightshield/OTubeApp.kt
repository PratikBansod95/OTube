package com.lightshield

import android.app.Application
import android.webkit.CookieManager
import com.lightshield.utils.LightCookieManager

class OTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            LightCookieManager.migrateCorruptBackupIfNeeded(this)
        } catch (_: Throwable) {
        }
    }
}
