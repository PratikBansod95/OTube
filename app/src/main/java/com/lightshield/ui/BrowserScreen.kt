package com.lightshield.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.lightshield.browser.LightWebView
import com.lightshield.util.YoutubeUrls

@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<LightWebView?>(null) }
    var initialLoaded by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("") }
    var lastWatchId by remember { mutableStateOf<String?>(null) }
    var wasOnWatch by remember { mutableStateOf(false) }
    var miniVideoId by remember { mutableStateOf<String?>(null) }

    fun onLocation(url: String) {
        currentUrl = url
        val id = YoutubeUrls.extractVideoId(url)
        val onWatch = id != null
        if (wasOnWatch && !onWatch) {
            // Left a watch page (back, home, related browse) → keep watching in floating player.
            lastWatchId?.let { miniVideoId = it }
        }
        if (onWatch) {
            lastWatchId = id
            // Expanded into full watch for this (or another) video → dismiss mini.
            miniVideoId = null
        }
        wasOnWatch = onWatch
    }

    fun minimizeCurrentWatch(): Boolean {
        val wv = webView ?: return false
        val id = YoutubeUrls.extractVideoId(wv.url ?: currentUrl) ?: lastWatchId
        if (id == null) return false
        lastWatchId = id
        miniVideoId = id
        when {
            wv.canGoBack() -> wv.goBack()
            else -> wv.loadUrl("https://www.youtube.com")
        }
        return true
    }

    BackHandler {
        val wv = webView
        when {
            wv == null -> (context as? Activity)?.finish()
            wv.exitCustomViewIfNeeded() -> Unit
            YoutubeUrls.isWatchUrl(wv.url ?: currentUrl) -> minimizeCurrentWatch()
            wv.canGoBack() -> wv.goBack()
            miniVideoId != null -> miniVideoId = null
            else -> (context as? Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                LightWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onLocationChanged = { url -> onLocation(url) }
                    webView = this
                }
            },
            update = { wv ->
                webView = wv
                wv.onLocationChanged = { url -> onLocation(url) }
                if (!initialLoaded) {
                    wv.loadUrl("https://www.youtube.com")
                    initialLoaded = true
                }
            }
        )

        val floatingId = miniVideoId
        if (floatingId != null) {
            FloatingMiniPlayer(
                videoId = floatingId,
                onClose = { miniVideoId = null },
                onExpand = {
                    miniVideoId = null
                    webView?.loadUrl(YoutubeUrls.watchUrl(floatingId))
                }
            )
        }
    }
}
