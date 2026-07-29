package com.lightshield.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lightshield.filters.FilterListManager
import com.lightshield.privacy.HttpsEnforcer
import com.lightshield.utils.LightCookieManager
import org.json.JSONArray

class LightWebView(context: Context) : WebView(context) {
    private val interceptor = RequestInterceptor(context)
    private val filters = FilterListManager.getInstance(context)
    private val httpsEnforcer = HttpsEnforcer()
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var documentUrl: String? = null

    @Volatile
    var isInCustomView: Boolean = false
        private set

    init {
        setLayerType(LAYER_TYPE_NONE, null)
        configureSettings()
        LightCookieManager.configureForPrivacy()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                val upgraded = httpsEnforcer.upgradeToHttpsIfPossible(target)
                if (upgraded != target) {
                    view?.loadUrl(upgraded)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) documentUrl = url
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrBlank()) documentUrl = url
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val url = request.url.toString()
                if (interceptor.shouldBlock(
                        url,
                        request.requestHeaders,
                        request.isForMainFrame,
                        documentUrl
                    )
                ) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Blocked",
                        emptyMap(),
                        null
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                documentUrl = url
                injectCosmeticFiltersIfNeeded(view, url)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                val activity = context as? Activity ?: return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                isInCustomView = true
                val window = activity.window
                val decor = window.decorView as FrameLayout
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = WindowCompat.getInsetsController(window, decor)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                val activity = context as? Activity ?: return
                val window = activity.window
                val decor = window.decorView as FrameLayout
                customView?.let { decor.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                isInCustomView = false
                val controller = WindowCompat.getInsetsController(window, decor)
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    fun exitCustomViewIfNeeded(): Boolean {
        if (!isInCustomView) return false
        webChromeClient?.onHideCustomView()
        return true
    }

    private fun configureSettings() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " OTube/1.0"

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false

        try {
            val fingerprint = android.os.Build.FINGERPRINT.lowercase()
            val model = android.os.Build.MODEL.lowercase()
            val hardware = android.os.Build.HARDWARE.lowercase()
            val isEmulator = fingerprint.contains("generic") ||
                model.contains("emulator") ||
                hardware.contains("ranchu") ||
                hardware.contains("goldfish")
            settings.setOffscreenPreRaster(!isEmulator)
        } catch (_: Throwable) {
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setBackgroundColor(Color.BLACK)
    }

    private fun injectCosmeticFiltersIfNeeded(view: WebView?, url: String) {
        val host = safeHost(url) ?: return
        val isYoutube = host.contains("youtube.com") || host.contains("youtu.be")

        val cosmetics = filters.cosmeticsForUrl(url)
        val braveSelectors = cosmetics.hideSelectors
            .asSequence()
            .filter { it.isNotBlank() && !it.contains("</") }
            .take(800)
            .toList()

        val youtubeFallback = if (isYoutube) YOUTUBE_FALLBACK_SELECTORS else emptyList()
        val allSelectors = (braveSelectors + youtubeFallback).distinct()

        val selectorJson = JSONArray(allSelectors).toString()
        val scriptletJson = org.json.JSONObject.quote(cosmetics.injectedScript)

        val js = """
            (function(){
              var selectors = $selectorJson;
              var css = selectors.map(function(s){ return s + '{display:none!important;}'; }).join('\n');
              var style = document.getElementById('otube-ad-style');
              if (!style) {
                style = document.createElement('style');
                style.id = 'otube-ad-style';
                (document.head || document.documentElement).appendChild(style);
              }
              style.textContent = css;

              var scriptlet = $scriptletJson;
              if (scriptlet && !window.__otubeScriptletApplied) {
                window.__otubeScriptletApplied = true;
                try { (0, eval)(scriptlet); } catch (e) {}
              }

              function skipAd() {
                var btn = document.querySelector(
                  '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button'
                );
                if (btn) btn.click();
                var video = document.querySelector('video');
                var player = document.querySelector('.html5-video-player.ad-showing');
                if (player && video && video.duration && isFinite(video.duration)) {
                  try { video.currentTime = video.duration; } catch (e) {}
                }
              }
              if (${if (isYoutube) "true" else "false"}) {
                skipAd();
                if (!window.__otubeAdObserver) {
                  window.__otubeAdObserver = new MutationObserver(function(){ skipAd(); });
                  window.__otubeAdObserver.observe(document.documentElement, {childList:true, subtree:true});
                }
              }
            })();
        """.trimIndent()
        view?.post { view.evaluateJavascript(js, null) }
    }

    private fun safeHost(url: String): String? =
        try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Throwable) {
            null
        }

    companion object {
        private val YOUTUBE_FALLBACK_SELECTORS = listOf(
            "ytd-ad-slot-renderer",
            "ytd-promoted-sparkles-web-renderer",
            "ytd-promoted-sparkles-text-search-renderer",
            "ytd-promoted-video-renderer",
            "ytd-action-companion-ad-renderer",
            "ytd-display-ad-renderer",
            "ytd-in-feed-ad-layout-renderer",
            "ytd-banner-promo-renderer",
            "ytd-statement-banner-renderer",
            "ytd-player-legacy-desktop-watch-ads-renderer",
            "ytm-promoted-sparkles-web-renderer",
            "ytm-promoted-video-renderer",
            "ytm-companion-ad-renderer",
            "#masthead-ad",
            "#player-ads",
            ".ytp-ad-module",
            ".ytp-ad-overlay-container",
            ".video-ads",
            ".ytp-ad-player-overlay",
            "ytd-mealbar-promo-renderer",
            "ytd-merch-shelf-renderer"
        )
    }
}
