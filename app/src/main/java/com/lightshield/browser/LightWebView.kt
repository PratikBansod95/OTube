package com.lightshield.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Rational
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    private var pipPollStarted = false
    private val pipPollRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            refreshPipEligibility { eligible ->
                onPlaybackEligibilityChanged?.invoke(eligible)
            }
            postDelayed(this, 2000L)
        }
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
        configureSettings()
        LightCookieManager.configureForPrivacy()
        installDocumentStartAdStripper()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                if (isYoutubeAppHandoff(target)) {
                    // Stay inside OTube — don't jump to the Play Store / YouTube app.
                    return true
                }
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
                        injectOpenAppSuppressor(view)
                        ensurePipEligibilityPolling()
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrBlank()) {
                    documentUrl = url
                    if (isYoutubeUrl(url)) {
                        injectPlayerRecovery(view)
                        injectOpenAppSuppressor(view)
                        ensurePipEligibilityPolling()
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
                    return emptyBlockedResponse(url, request.method, request.requestHeaders)
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
                    injectOpenAppSuppressor(view)
                    ensurePipEligibilityPolling()
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

    var onPipModeChangedListener: ((Boolean) -> Unit)? = null
    var onPlaybackEligibilityChanged: ((Boolean) -> Unit)? = null

    fun onPipModeChanged(inPip: Boolean) {
        if (inPip) {
            applyPipLayout(true)
        } else {
            applyPipLayout(false)
        }
        onPipModeChangedListener?.invoke(inPip)
    }

    fun queryPlaybackForPip(callback: (playing: Boolean, aspect: Rational?) -> Unit) {
        val js = """
            (function(){
              var v = document.querySelector('video');
              if (!v || v.paused || v.ended || v.readyState < 2) return '0|16|9';
              var w = v.videoWidth || 16;
              var h = v.videoHeight || 9;
              if (w < 1) w = 16;
              if (h < 1) h = 9;
              return '1|' + w + '|' + h;
            })();
        """.trimIndent()
        evaluateJavascript(js) { raw ->
            val s = raw?.trim()?.removeSurrounding("\"")?.replace("\\u003C", "<") ?: "0|16|9"
            val parts = s.split('|')
            val playing = parts.getOrNull(0) == "1"
            val w = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 16
            val h = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 9
            post { callback(playing, if (playing) Rational(w, h) else null) }
        }
    }

    fun refreshPipEligibility(callback: (Boolean) -> Unit) {
        queryPlaybackForPip { playing, _ -> callback(playing) }
    }

    /** Poll playback so Android 12+ can auto-enter PiP on Home/gesture. */
    fun ensurePipEligibilityPolling() {
        if (pipPollStarted) return
        pipPollStarted = true
        post(pipPollRunnable)
    }

    private fun applyPipLayout(enabled: Boolean) {
        val js = if (enabled) {
            """
            (function(){
              if (window.__otubePipLayout) return;
              window.__otubePipLayout = true;
              var style = document.getElementById('otube-pip-style');
              if (!style) {
                style = document.createElement('style');
                style.id = 'otube-pip-style';
                (document.head || document.documentElement).appendChild(style);
              }
              style.textContent = [
                'html.otube-pip, html.otube-pip body { background:#000!important; overflow:hidden!important; }',
                'html.otube-pip ytm-mobile-topbar-renderer,',
                'html.otube-pip ytm-pivot-bar-renderer,',
                'html.otube-pip ytm-engagement-panel,',
                'html.otube-pip #related,',
                'html.otube-pip ytm-item-section-renderer,',
                'html.otube-pip ytm-comment-section-renderer,',
                'html.otube-pip .watch-below-the-player,',
                'html.otube-pip #masthead-container,',
                'html.otube-pip #secondary,',
                'html.otube-pip ytd-watch-next-secondary-results-renderer,',
                'html.otube-pip #chat,',
                'html.otube-pip #comments { display:none!important; }',
                'html.otube-pip video {',
                '  position:fixed!important; left:0!important; top:0!important;',
                '  width:100vw!important; height:100vh!important; z-index:2147483646!important;',
                '  object-fit:contain!important; background:#000!important; max-height:100vh!important;',
                '}'
              ].join('\\n');
              document.documentElement.classList.add('otube-pip');
              var v = document.querySelector('video');
              if (v) { try { v.play(); } catch (e) {} }
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              window.__otubePipLayout = false;
              document.documentElement.classList.remove('otube-pip');
              var style = document.getElementById('otube-pip-style');
              if (style) style.remove();
            })();
            """.trimIndent()
        }
        post { evaluateJavascript(js, null) }
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
     * Fast-fail blocked ads. Do NOT return 200 + "{}": YouTube treats that as success
     * and can sit on the loading spinner for ~15s while it retries/parses bad ad data.
     * 204/404 with CORS lets credentialed XHR error out immediately.
     */
    private fun emptyBlockedResponse(
        url: String,
        method: String?,
        requestHeaders: Map<String, String>?
    ): WebResourceResponse {
        val origin = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: "*"
        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to origin,
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        )
        if (origin != "*") {
            headers["Access-Control-Allow-Credentials"] = "true"
            headers["Vary"] = "Origin"
        }

        // Preflight: approve quickly so the real call can fail fast.
        if (method.equals("OPTIONS", ignoreCase = true)) {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "No Content",
                headers,
                ByteArrayInputStream(ByteArray(0))
            )
        }

        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Blocked",
            headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Strip ad payloads from YouTube player JSON before page scripts run.
     * This prevents many preroll/midroll ads without seeking the content video.
     */
    private fun installDocumentStartAdStripper() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = """
            (function(){
              if (window.__otubeAdStrip) return;
              window.__otubeAdStrip = true;
              var origParse = JSON.parse;

              function clean(obj) {
                if (!obj || typeof obj !== 'object') return obj;
                try {
                  // Only touch objects that look like player responses.
                  var looksLikePlayer = !!(obj.videoDetails || obj.streamingData || obj.playerResponse ||
                    obj.adPlacements || obj.playerAds);
                  if (!looksLikePlayer) return obj;
                  if (Object.prototype.hasOwnProperty.call(obj, 'adPlacements')) obj.adPlacements = [];
                  if (Object.prototype.hasOwnProperty.call(obj, 'playerAds')) obj.playerAds = [];
                  if (Object.prototype.hasOwnProperty.call(obj, 'adSlots')) obj.adSlots = [];
                  if (Object.prototype.hasOwnProperty.call(obj, 'adBreakHeartbeatParams')) delete obj.adBreakHeartbeatParams;
                  if (obj.playerResponse) clean(obj.playerResponse);
                  if (obj.player && obj.player.args) {
                    var raw = obj.player.args.raw_player_response;
                    if (typeof raw === 'string') {
                      try {
                        var parsed = origParse.call(JSON, raw);
                        clean(parsed);
                        obj.player.args.raw_player_response = JSON.stringify(parsed);
                      } catch (e) {}
                    } else if (raw && typeof raw === 'object') {
                      clean(raw);
                    }
                  }
                } catch (e) {}
                return obj;
              }

              JSON.parse = function(text, reviver) {
                var value = origParse.call(this, text, reviver);
                try { clean(value); } catch (e) {}
                return value;
              };

              try {
                var tipr;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                  configurable: true,
                  enumerable: true,
                  get: function(){ return tipr; },
                  set: function(v){ tipr = clean(v); }
                });
              } catch (e) {}
            })();
        """.trimIndent()
        try {
            WebViewCompat.addDocumentStartJavaScript(
                this,
                script,
                setOf(
                    "https://*.youtube.com",
                    "https://youtube.com",
                    "https://m.youtube.com",
                    "https://www.youtube.com",
                    "https://music.youtube.com",
                    "https://youtu.be"
                )
            )
            // Hide "Open App" promo chrome as early as possible.
            WebViewCompat.addDocumentStartJavaScript(
                this,
                OPEN_APP_DOCUMENT_START_SCRIPT,
                setOf(
                    "https://*.youtube.com",
                    "https://youtube.com",
                    "https://m.youtube.com",
                    "https://www.youtube.com",
                    "https://music.youtube.com",
                    "https://youtu.be"
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Continuously hide YouTube's "Open App" chip / banners that SPA navigations re-add.
     */
    private fun injectOpenAppSuppressor(view: WebView?) {
        view?.post { view.evaluateJavascript(OPEN_APP_SUPPRESSOR_JS, null) }
    }

    private fun isYoutubeAppHandoff(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("intent:")) return true
        if (lower.startsWith("vnd.youtube:") || lower.startsWith("youtube://")) return true
        if (lower.startsWith("market://")) {
            return lower.contains("id=com.google.android.youtube") ||
                lower.contains("id=com.google.android.apps.youtube")
        }
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path?.lowercase().orEmpty()
            val query = uri.query?.lowercase().orEmpty()
            when {
                host == "play.google.com" &&
                    (query.contains("id=com.google.android.youtube") ||
                        query.contains("id=com.google.android.apps.youtube")) -> true
                host.endsWith("youtube.com") && path.contains("/redirect") &&
                    query.contains("q=vnd.youtube") -> true
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun injectPlayerRecovery(view: WebView?) {
        // Only act when an ad is clearly showing — speed through / skip without
        // touching normal content playback (avoids mid-video black flashes).
        val js = """
            (function(){
              if (window.__otubePlayerRecovery) return;
              window.__otubePlayerRecovery = true;
              var wasAd = false;

              function isClearlyAd() {
                var player = document.querySelector('#movie_player.html5-video-player, .html5-video-player');
                if (!player || !player.classList.contains('ad-showing')) return false;
                return !!document.querySelector(
                  '.ytp-ad-player-overlay, .ytp-ad-progress-list, .ytp-ad-text, .ytp-ad-preview-container, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .video-ads.ytp-ad-module'
                );
              }

              function tick() {
                var video = document.querySelector('video');
                var ad = isClearlyAd();
                if (ad) {
                  wasAd = true;
                  var btn = document.querySelector(
                    '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, .ytp-ad-overlay-close-button'
                  );
                  if (btn) { try { btn.click(); } catch (e) {} }
                  if (video) {
                    try { video.muted = true; } catch (e) {}
                    try { video.playbackRate = 8; } catch (e) {}
                    // Avoid seeking to duration — that often tears down the decoder
                    // and leaves a long loading spinner before content resumes.
                  }
                } else if (wasAd && video) {
                  wasAd = false;
                  try { video.playbackRate = 1; } catch (e) {}
                  try { video.muted = false; } catch (e) {}
                }
              }

              tick();
              setInterval(tick, 500);
            })();
        """.trimIndent()
        view?.post { view.evaluateJavascript(js, null) }
    }

    private fun injectCosmeticFiltersIfNeeded(view: WebView?, url: String) {
        val host = safeHost(url) ?: return
        val isYoutube = host.contains("youtube.com") || host.contains("youtu.be")

        val cosmetics = filters.cosmeticsForUrl(url)
        val nativeSelectors = cosmetics.hideSelectors
            .asSequence()
            .filter { it.isNotBlank() && !it.contains("</") }
            .filter { !isUnsafePlayerSelector(it) }
            .take(800)
            .toList()

        val youtubeFallback = if (isYoutube) YOUTUBE_FALLBACK_SELECTORS else emptyList()
        val allSelectors = (nativeSelectors + youtubeFallback).distinct()

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

    /** Drop cosmetic selectors that can blank the actual player mid-playback. */
    private fun isUnsafePlayerSelector(selector: String): Boolean {
        val s = selector.lowercase().trim()
        if (s == "video" || s.startsWith("video.") || s.startsWith("video[") || s.startsWith("video:")) {
            return true
        }
        if (s.contains("html5-video-player") || s.contains("html5-video-container")) return true
        if (s.contains("ytp-html5") || s.contains("#movie_player") || s.contains("movie_player")) return true
        if (s.contains("ytd-player#") || s.contains("ytd-player.") || s == "ytd-player") return true
        return false
    }

    private fun safeHost(url: String): String? =
        try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Throwable) {
            null
        }

    companion object {
        private val YOUTUBE_FALLBACK_SELECTORS = listOf(
            // Ads / promos
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
            "ytd-merch-shelf-renderer",
            // "Open App" / app-install prompts
            "ytm-open-in-app-button",
            "ytm-open-in-app-header",
            "ytm-promo-panel-renderer",
            "ytm-app-promo-renderer",
            "ytm-pivot-bar-renderer[tab-identifier=\"FEapp_promo\"]",
            ".open-app-button",
            "button[aria-label=\"Open App\"]",
            "button[aria-label=\"Open app\"]",
            "a[href*=\"play.google.com/store/apps/details?id=com.google.android.youtube\"]",
            "a[href^=\"intent:\"]",
            "a[href^=\"vnd.youtube:\"]"
        )

        private val OPEN_APP_DOCUMENT_START_SCRIPT = """
            (function(){
              if (window.__otubeHideOpenAppEarly) return;
              window.__otubeHideOpenAppEarly = true;
              var css = [
                'ytm-open-in-app-button',
                'ytm-open-in-app-header',
                'ytm-promo-panel-renderer',
                'ytm-app-promo-renderer',
                '.open-app-button',
                'button[aria-label="Open App"]',
                'button[aria-label="Open app"]',
                'a[href*="play.google.com/store/apps/details?id=com.google.android.youtube"]',
                'a[href^="intent:"]',
                'a[href^="vnd.youtube:"]'
              ].map(function(s){ return s + '{display:none!important;visibility:hidden!important;pointer-events:none!important;}'; }).join('\n');
              var style = document.createElement('style');
              style.id = 'otube-hide-open-app';
              style.textContent = css;
              (document.documentElement || document).appendChild(style);
            })();
        """.trimIndent()

        private val OPEN_APP_SUPPRESSOR_JS = """
            (function(){
              if (window.__otubeOpenAppSuppressor) return;
              window.__otubeOpenAppSuppressor = true;

              function looksLikeOpenApp(el) {
                if (!el || el.nodeType !== 1) return false;
                var label = ((el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'))) || '').toLowerCase();
                if (label === 'open app' || label.indexOf('open app') !== -1) return true;
                var text = (el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (text === 'open app') return true;
                var href = ((el.getAttribute && el.getAttribute('href')) || '').toLowerCase();
                if (href.indexOf('play.google.com/store/apps/details?id=com.google.android.youtube') !== -1) return true;
                if (href.indexOf('intent:') === 0 || href.indexOf('vnd.youtube:') === 0 || href.indexOf('youtube://') === 0) return true;
                return false;
              }

              function hide(el) {
                try {
                  el.style.setProperty('display', 'none', 'important');
                  el.style.setProperty('visibility', 'hidden', 'important');
                  el.style.setProperty('pointer-events', 'none', 'important');
                  el.setAttribute('aria-hidden', 'true');
                } catch (e) {}
              }

              function sweep() {
                var nodes = document.querySelectorAll('a, button, ytm-button-renderer, ytm-open-in-app-button, [role="button"]');
                for (var i = 0; i < nodes.length; i++) {
                  if (looksLikeOpenApp(nodes[i])) hide(nodes[i]);
                }
              }

              sweep();
              try {
                var mo = new MutationObserver(function(){ sweep(); });
                mo.observe(document.documentElement || document.body, { childList: true, subtree: true });
              } catch (e) {
                setInterval(sweep, 1500);
              }
            })();
        """.trimIndent()
    }
}
