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
import com.lightshield.privacy.HttpsEnforcer
import com.lightshield.utils.LightCookieManager

class LightWebView(context: Context) : WebView(context) {
    private val interceptor = RequestInterceptor(context)
    private val httpsEnforcer = HttpsEnforcer()
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    @Volatile
    private var documentHost: String? = null
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
                documentHost = url?.let { safeHost(it) }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // YouTube SPA navigations often skip full reloads; keep host fresh.
                if (!url.isNullOrBlank()) {
                    documentHost = safeHost(url) ?: documentHost
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
                        documentHost
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
                documentHost = safeHost(url) ?: documentHost
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
        if (!host.contains("youtube.com") && !host.contains("youtu.be") &&
            !host.contains("music.youtube.com")
        ) {
            return
        }

        // Targeted YouTube ad selectors only — avoid broad [id*="ad"] which breaks UI.
        val js = """
            (function(){
              var css = `
                ytd-ad-slot-renderer,
                ytd-promoted-sparkles-web-renderer,
                ytd-promoted-sparkles-text-search-renderer,
                ytd-promoted-video-renderer,
                ytd-action-companion-ad-renderer,
                ytd-display-ad-renderer,
                ytd-in-feed-ad-layout-renderer,
                ytd-banner-promo-renderer,
                ytd-statement-banner-renderer,
                ytd-player-legacy-desktop-watch-ads-renderer,
                ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],
                ytm-promoted-sparkles-web-renderer,
                ytm-promoted-video-renderer,
                ytm-companion-ad-renderer,
                ytm-banner-promo-renderer,
                #masthead-ad,
                #player-ads,
                #offer-module,
                .ytp-ad-module,
                .ytp-ad-overlay-container,
                .ytp-ad-progress-list,
                .ytp-ad-message-container,
                .video-ads,
                .ytp-ad-player-overlay,
                .ytp-ad-player-overlay-layout,
                tp-yt-paper-dialog.ytd-popup-container,
                ytd-mealbar-promo-renderer,
                ytd-merch-shelf-renderer {
                  display: none !important;
                }
              `;
              var style = document.getElementById('otube-ad-style');
              if (!style) {
                style = document.createElement('style');
                style.id = 'otube-ad-style';
                (document.head || document.documentElement).appendChild(style);
              }
              if (style.textContent !== css) style.textContent = css;

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
              skipAd();
              if (!window.__otubeAdObserver) {
                window.__otubeAdObserver = new MutationObserver(function(){ skipAd(); });
                window.__otubeAdObserver.observe(document.documentElement, {childList:true, subtree:true});
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
}
