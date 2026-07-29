package com.lightshield.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRuleUnitTest {

    @Test
    fun hostAnchorMatchesSubdomain() {
        val rule = FilterListManager.Rule(hostAnchor = "doubleclick.net")
        assertTrue(
            rule.matches(
                "https://ad.doubleclick.net/ddm/track",
                "ad.doubleclick.net",
                "image",
                true
            )
        )
        assertFalse(
            rule.matches(
                "https://notdoubleclick.net/x",
                "notdoubleclick.net",
                "image",
                true
            )
        )
    }

    @Test
    fun thirdPartyOptionRespected() {
        val rule = FilterListManager.Rule(
            hostAnchor = "hotjar.com",
            options = FilterListManager.RuleOptions(thirdParty = true)
        )
        assertTrue(rule.matches("https://hotjar.com/s.js", "hotjar.com", "script", true))
        assertFalse(rule.matches("https://hotjar.com/s.js", "hotjar.com", "script", false))
    }

    @Test
    fun extractHostParsesUrl() {
        assertEquals(
            "www.youtube.com",
            FilterListManager.extractHost("https://www.youtube.com/watch?v=1")
        )
    }
}

class ThirdPartyCheckUnitTest {
    @Test
    fun suffixBoundaryIsRespected() {
        fun isThirdParty(requestHost: String, documentHost: String): Boolean {
            val req = requestHost.lowercase()
            val doc = documentHost.lowercase()
            if (req == doc) return false
            if (req.endsWith(".$doc")) return false
            return true
        }
        assertTrue(isThirdParty("notyoutube.com", "youtube.com"))
        assertFalse(isThirdParty("m.youtube.com", "youtube.com"))
        assertFalse(isThirdParty("youtube.com", "youtube.com"))
    }
}
