package com.lightshield.browser

import android.content.Context
import android.net.Uri
import com.lightshield.filters.FilterListManager

/**
 * Intercepts WebView subresource requests and blocks ads/trackers via FilterListManager.
 */
class RequestInterceptor(context: Context) {
    private val filters = FilterListManager.getInstance(context)

    fun shouldBlock(
        url: String,
        headers: Map<String, String>?,
        isMainFrame: Boolean,
        documentHost: String?
    ): Boolean {
        if (isMainFrame) return false
        // Never block YouTube media streaming by scheme/path heuristics handled in filters
        val resourceType = detectResourceType(url, headers)
        val thirdParty = isThirdParty(url, documentHost)
        return filters.isBlocked(url, resourceType, thirdParty)
    }

    private fun detectResourceType(url: String, headers: Map<String, String>?): String {
        val path = try {
            Uri.parse(url).path?.lowercase().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        when {
            path.endsWith(".js") -> return "script"
            path.endsWith(".css") -> return "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") -> return "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> return "font"
            path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".webm") ||
                path.contains("/videoplayback") -> return "media"
        }

        val accept = headers?.entries
            ?.firstOrNull { it.key.equals("Accept", true) }
            ?.value
            ?: return "other"

        return when {
            accept.contains("text/html") -> "subdocument"
            accept.contains("image/") -> "image"
            accept.contains("text/css") -> "stylesheet"
            accept.contains("javascript") -> "script"
            accept.contains("application/json") || accept.contains("text/event-stream") -> "xhr"
            accept.contains("font/") || accept.contains("application/font") -> "font"
            accept.contains("video/") || accept.contains("audio/") -> "media"
            else -> "other"
        }
    }

    /**
     * True when the request host is not the document host and not a subdomain of it
     * (and vice versa). Uses label-boundary checks to avoid `notyoutube.com` matching
     * `youtube.com`.
     */
    fun isThirdParty(requestUrl: String, documentHost: String?): Boolean {
        if (documentHost.isNullOrBlank()) return true
        val reqHost = try {
            Uri.parse(requestUrl).host?.lowercase() ?: return true
        } catch (_: Throwable) {
            return true
        }
        val doc = documentHost.lowercase()
        if (reqHost == doc) return false
        if (reqHost.endsWith(".$doc")) return false
        // Same eTLD+1 for common Google/YouTube family counts as first-party for UX
        if (sameSiteFamily(reqHost, doc)) return false
        return true
    }

    private fun sameSiteFamily(a: String, b: String): Boolean {
        val family = listOf("youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "ggpht.com")
        val aIn = family.any { a == it || a.endsWith(".$it") }
        val bIn = family.any { b == it || b.endsWith(".$it") }
        return aIn && bIn
    }
}
