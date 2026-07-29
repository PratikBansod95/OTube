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
import java.io.ByteArrayInputStream

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
                if (!url.isNullOrBlank()) {
                    documentUrl = url
                    // Install early so SPA navigations / stalled players recover sooner.
                    if (isYoutubeUrl(url)) {
                        injectPlayerRecovery(view)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrBlank()) {
                    documentUrl = url
                    if (isYoutubeUrl(url)) {
                        injectPlayerRecovery(view)
                    }
                }
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
                    // Return empty 200 + CORS so YouTube's player fails closed quickly
                    // instead of hanging on a black frame waiting for a 403/CORS error.
                    return emptyBlockedResponse(url, request.method)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                documentUrl = url
                injectCosmeticFiltersIfNeeded(view, url)
                if (isYoutubeUrl(url)) {
                    injectPlayerRecovery(view)
                }
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

    /**
     * Empty success response with permissive CORS. Prefer this over 403: YouTube XHR
     * often waits/retries on failed ad calls and leaves the player black until timeout.
     */
    private fun emptyBlockedResponse(url: String, method: String?): WebResourceResponse {
        val lower = url.lowercase()
        val (mime, body) = when {
            method.equals("OPTIONS", ignoreCase = true) ->
                "text/plain" to ByteArray(0)
            lower.contains(".js") || lower.contains("javascript") ->
                "application/javascript" to ByteArray(0)
            lower.contains(".json") || lower.contains("/pagead/") ||
                lower.contains("googleadservices") || lower.contains("doubleclick") ->
                "application/json" to "{}".toByteArray()
            lower.contains(".css") ->
                "text/css" to ByteArray(0)
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".gif") ||
                lower.contains(".webp") || lower.contains("image") ->
                "image/png" to ByteArray(0)
            else -> "text/plain" to ByteArray(0)
        }
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        )
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(body))
    }

    private fun injectPlayerRecovery(view: WebView?) {
        val js = """
            (function(){
              if (window.__otubePlayerRecovery) return;
              window.__otubePlayerRecovery = true;

              function clickSkip() {
                var btn = document.querySelector(
                  '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, .ytp-ad-overlay-close-button'
                );
                if (btn) { try { btn.click(); } catch (e) {} }
              }

              function forcePlay() {
                var video = document.querySelector('video');
                if (!video) return;
                try {
                  var p = video.play();
                  if (p && p.catch) p.catch(function(){});
                } catch (e) {}
              }

              function skipAdFast() {
                var player = document.querySelector('.html5-video-player.ad-showing, .ad-showing');
                var video = document.querySelector('video');
                clickSkip();
                if (player && video) {
                  // Prefer skip button; only seek when duration is known and short (typical ads).
                  try {
                    if (isFinite(video.duration) && video.duration > 0 && video.duration < 120) {
                      video.currentTime = Math.max(0, video.duration - 0.2);
                    }
                  } catch (e) {}
                  forcePlay();
                }
              }

              // If the main video sits black/paused with data, nudge playback.
              function recoverStalled() {
                var video = document.querySelector('video');
                if (!video) return;
                var player = document.querySelector('.html5-video-player');
                var showingAd = !!(player && player.classList.contains('ad-showing'));
                if (showingAd) { skipAdFast(); return; }
                if (video.readyState >= 2 && video.paused && !video.ended) {
                  forcePlay();
                }
              }

              skipAdFast();
              forcePlay();
              setInterval(function(){ skipAdFast(); recoverStalled(); }, 700);
              if (!window.__otubeAdObserver) {
                window.__otubeAdObserver = new MutationObserver(function(){ skipAdFast(); });
                window.__otubeAdObserver.observe(document.documentElement, {childList:true, subtree:true});
              }
            })();
        """.trimIndent()
        view?.post { view.evaluateJavascript(js, null) }
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
            })();
        """.trimIndent()
        view?.post { view.evaluateJavascript(js, null) }
    }

    private fun isYoutubeUrl(url: String): Boolean {
        val host = safeHost(url) ?: return false
        return host.contains("youtube.com") || host.contains("youtu.be")
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
