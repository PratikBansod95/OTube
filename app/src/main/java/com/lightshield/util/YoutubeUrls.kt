package com.lightshield.util

object YoutubeUrls {
    private val WATCH_V = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""")
    private val EMBED_OR_SHORTS = Regex("""/(?:embed|shorts|live)/([A-Za-z0-9_-]{6,})""")
    private val YOUTU_BE = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""")

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val lower = url
        WATCH_V.find(lower)?.groupValues?.getOrNull(1)?.let { return it }
        EMBED_OR_SHORTS.find(lower)?.groupValues?.getOrNull(1)?.let { return it }
        YOUTU_BE.find(lower)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    fun isWatchUrl(url: String?): Boolean = extractVideoId(url) != null

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun embedUrl(videoId: String): String =
        "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
}
