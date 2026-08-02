package com.lightshield

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lightshield.browser.LightWebView
import com.lightshield.ui.BrowserScreen
import com.lightshield.ui.theme.OTubeTheme

class MainActivity : ComponentActivity() {
    @Volatile
    var browser: LightWebView? = null

    private var pipEligible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OTubeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(
                        onWebViewReady = { wv -> browser = wv },
                        onRequestPip = { tryEnterPip(force = true) }
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterPip(force = false)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        browser?.onPipModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            refreshPipParams(eligible = pipEligible)
        }
    }

    /** Keep system auto-enter PiP in sync with playback (API 31+). */
    fun refreshPipParams(eligible: Boolean) {
        pipEligible = eligible
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(DEFAULT_ASPECT)
            .setAutoEnterEnabled(eligible)
            .build()
        setPictureInPictureParams(params)
    }

    fun tryEnterPip(force: Boolean) {
        if (isInPictureInPictureMode) return
        val wv = browser ?: return
        wv.exitCustomViewIfNeeded()
        wv.queryPlaybackForPip { playing, aspect ->
            // Only enter when a video is actually playing (FAB and Home alike).
            if (!playing) return@queryPlaybackForPip
            enterPip(aspect ?: DEFAULT_ASPECT)
        }
    }

    private fun enterPip(aspect: Rational) {
        if (isInPictureInPictureMode) return
        val clamped = clampAspect(aspect)
        val builder = PictureInPictureParams.Builder().setAspectRatio(clamped)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        val params = builder.build()
        try {
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        } catch (_: IllegalStateException) {
            // Device/OEM may reject PiP in some states.
        }
    }

    companion object {
        private val DEFAULT_ASPECT = Rational(16, 9)

        private fun clampAspect(aspect: Rational): Rational {
            val value = aspect.toFloat()
            // Android requires aspect ratio between 0.418 and 2.39 approximately.
            return when {
                value < 0.5f -> Rational(9, 16)
                value > 2.3f -> Rational(16, 9)
                else -> aspect
            }
        }
    }
}
