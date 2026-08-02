package com.lightshield

import com.lightshield.util.YoutubeUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeUrlsTest {
    @Test
    fun extractsWatchQuery() {
        assertEquals(
            "dQw4w9WgXcQ",
            YoutubeUrls.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun extractsShortsAndEmbed() {
        assertEquals(
            "abc12345XY",
            YoutubeUrls.extractVideoId("https://m.youtube.com/shorts/abc12345XY")
        )
        assertEquals(
            "abc12345XY",
            YoutubeUrls.extractVideoId("https://www.youtube.com/embed/abc12345XY?autoplay=1")
        )
    }

    @Test
    fun extractsYoutuBe() {
        assertEquals(
            "dQw4w9WgXcQ",
            YoutubeUrls.extractVideoId("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun ignoresHome() {
        assertNull(YoutubeUrls.extractVideoId("https://www.youtube.com/"))
        assertNull(YoutubeUrls.extractVideoId("https://m.youtube.com/feed/subscriptions"))
    }
}
