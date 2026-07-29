package com.lightshield.privacy

/**
 * Upgrades cleartext navigations to HTTPS. The app also disables cleartext at the
 * manifest level; this covers in-page http:// links.
 */
class HttpsEnforcer {
    fun upgradeToHttpsIfPossible(url: String): String {
        if (url.startsWith("http://")) {
            return "https://" + url.removePrefix("http://")
        }
        return url
    }
}
