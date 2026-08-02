package com.lightshield.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lightshield.MainActivity
import com.lightshield.browser.LightWebView

@Composable
fun BrowserScreen(
    onWebViewReady: (LightWebView) -> Unit = {},
    onRequestPip: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var webView by remember { mutableStateOf<LightWebView?>(null) }
    var initialLoaded by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }

    DisposableEffect(activity, webView) {
        val act = activity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inPip = act?.isInPictureInPictureMode == true
                webView?.refreshPipEligibility { eligible ->
                    act?.refreshPipParams(eligible)
                }
            }
        }
        act?.lifecycle?.addObserver(observer)
        onDispose {
            act?.lifecycle?.removeObserver(observer)
            if (act?.browser === webView) {
                act?.browser = null
            }
        }
    }

    BackHandler {
        val wv = webView
        when {
            wv == null -> (context as? Activity)?.finish()
            wv.exitCustomViewIfNeeded() -> Unit
            wv.canGoBack() -> wv.goBack()
            else -> (context as? Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (inPip) Modifier else Modifier.statusBarsPadding())
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                LightWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onPipModeChangedListener = { pip -> inPip = pip }
                    onPlaybackEligibilityChanged = { eligible ->
                        activity?.refreshPipParams(eligible)
                    }
                    webView = this
                    onWebViewReady(this)
                }
            },
            update = { wv ->
                webView = wv
                activity?.browser = wv
                if (!initialLoaded) {
                    wv.loadUrl("https://www.youtube.com")
                    initialLoaded = true
                }
            }
        )

        if (!inPip) {
            FloatingActionButton(
                onClick = onRequestPip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text(text = "PiP")
            }
        }
    }
}
