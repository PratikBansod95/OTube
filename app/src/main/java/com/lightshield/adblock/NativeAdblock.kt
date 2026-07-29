package com.lightshield.adblock

import android.util.Log
import org.json.JSONObject

/**
 * JNI facade over Brave's adblock-rust ([libadblock_ffi.so]).
 * Falls back gracefully when the native library is missing.
 */
object NativeAdblock {
    private const val TAG = "NativeAdblock"

    @Volatile
    var isLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("adblock_ffi")
            isLibraryLoaded = true
            Log.i(TAG, "Loaded libadblock_ffi.so")
        } catch (t: Throwable) {
            isLibraryLoaded = false
            Log.w(TAG, "Native adblock unavailable, using Kotlin fallback: ${t.message}")
        }
    }

    fun create(rulesText: String): Long {
        if (!isLibraryLoaded) return 0L
        return try {
            nativeCreate(rulesText)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeCreate failed", t)
            0L
        }
    }

    fun destroy(handle: Long) {
        if (!isLibraryLoaded || handle == 0L) return
        try {
            nativeDestroy(handle)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeDestroy failed", t)
        }
    }

    fun shouldBlock(handle: Long, url: String, sourceUrl: String, requestType: String): Boolean {
        if (!isLibraryLoaded || handle == 0L) return false
        return try {
            nativeShouldBlock(handle, url, sourceUrl, requestType)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeShouldBlock failed", t)
            false
        }
    }

    fun cosmetics(handle: Long, url: String): CosmeticResources {
        if (!isLibraryLoaded || handle == 0L) return CosmeticResources.EMPTY
        return try {
            val raw = nativeUrlCosmetics(handle, url) ?: return CosmeticResources.EMPTY
            parseCosmetics(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeUrlCosmetics failed", t)
            CosmeticResources.EMPTY
        }
    }

    private fun parseCosmetics(raw: String): CosmeticResources {
        val obj = JSONObject(raw)
        val selectors = mutableListOf<String>()
        val arr = obj.optJSONArray("hide_selectors")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) selectors.add(s)
            }
        }
        return CosmeticResources(
            hideSelectors = selectors,
            injectedScript = obj.optString("injected_script", ""),
            generichide = obj.optBoolean("generichide", false)
        )
    }

    data class CosmeticResources(
        val hideSelectors: List<String>,
        val injectedScript: String,
        val generichide: Boolean
    ) {
        companion object {
            val EMPTY = CosmeticResources(emptyList(), "", false)
        }
    }

    @JvmStatic
    private external fun nativeCreate(rules: String): Long

    @JvmStatic
    private external fun nativeDestroy(handle: Long)

    @JvmStatic
    private external fun nativeShouldBlock(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String
    ): Boolean

    @JvmStatic
    private external fun nativeUrlCosmetics(handle: Long, url: String): String?
}
