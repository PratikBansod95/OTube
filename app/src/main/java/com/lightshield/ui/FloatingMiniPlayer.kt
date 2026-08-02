package com.lightshield.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.lightshield.util.YoutubeUrls
import kotlin.math.roundToInt

/**
 * In-app floating miniplayer (YouTube-app style), not Android system PiP.
 */
@Composable
fun FloatingMiniPlayer(
    videoId: String,
    onClose: () -> Unit,
    onExpand: () -> Unit
) {
    val density = LocalDensity.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var paused by remember { mutableStateOf(false) }

    // Default dock: bottom-end-ish; user can drag anywhere inside the app.
    DisposableEffect(Unit) {
        offsetX = with(density) { 12.dp.toPx() }
        offsetY = with(density) { 420.dp.toPx() }
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    Box(
        modifier = Modifier
            .zIndex(10f)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(200.dp)
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(ComposeColor(0xFF0F0F0F))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clickable(onClick = onExpand)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createMiniWebView(ctx).also { wv ->
                        webView = wv
                        wv.loadUrl(YoutubeUrls.embedUrl(videoId))
                    }
                },
                update = { wv ->
                    webView = wv
                    val wanted = YoutubeUrls.embedUrl(videoId)
                    if (wv.url?.contains(videoId) != true) {
                        wv.loadUrl(wanted)
                    }
                }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(ComposeColor(0x66000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (paused) "▶" else "❚❚",
                color = ComposeColor.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable {
                        val next = !paused
                        paused = next
                        webView?.togglePlayback(pause = next)
                    }
                    .padding(6.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "✕",
                color = ComposeColor.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(6.dp)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMiniWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " OTubeMini/1.0"
        webViewClient = WebViewClient()
        webChromeClient = WebChromeClient()
    }
}

private fun WebView.togglePlayback(pause: Boolean) {
    val js = if (pause) {
        "(function(){var v=document.querySelector('video'); if(v) v.pause();})();"
    } else {
        "(function(){var v=document.querySelector('video'); if(v) v.play();})();"
    }
    evaluateJavascript(js, null)
}
