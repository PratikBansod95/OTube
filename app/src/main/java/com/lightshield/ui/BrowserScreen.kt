package com.lightshield.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.lightshield.browser.LightWebView
import com.lightshield.utils.LightCookieManager

@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    var webView by remember { mutableStateOf<LightWebView?>(null) }
    var initialLoaded by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    // Must be synchronous — process may die immediately after.
                    LightCookieManager.persistCookies(context.applicationContext)
                }
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            LightCookieManager.persistCookies(context.applicationContext)
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

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        factory = { ctx ->
            LightWebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView = this
            }
        },
        update = { wv ->
            webView = wv
            if (!initialLoaded) {
                // Restore again right before first navigation.
                LightCookieManager.restorePersistedCookies(context.applicationContext)
                wv.loadUrl("https://www.youtube.com")
                initialLoaded = true
            }
        }
    )
}
