package com.lightshield.filters

import android.content.Context
import android.util.Log
import com.lightshield.adblock.NativeAdblock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads EasyList / EasyPrivacy and blocks with the native adblock-rust engine when the
 * native library is present. Falls back to a Kotlin ABP subset otherwise.
 */
class FilterListManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val loaded = AtomicBoolean(false)

    private val nativeHandle = AtomicLong(0L)
    private val kotlinEngine = AtomicReference(KotlinEngine.EMPTY)

    @Volatile
    var usingNativeEngine: Boolean = false
        private set

    init {
        // Touch NativeAdblock early so loadLibrary runs off the critical path once.
        NativeAdblock.isLibraryLoaded
        scope.launch { ensureLoaded() }
    }

    fun isBlocked(url: String, type: String, thirdParty: Boolean, documentUrl: String?): Boolean {
        if (isEssentialYoutube(url) || isEssentialGoogleAuth(url)) return false
        if (!loaded.get()) return false

        val handle = nativeHandle.get()
        if (handle != 0L && NativeAdblock.isLibraryLoaded) {
            val source = documentUrl?.takeIf { it.isNotBlank() } ?: url
            return NativeAdblock.shouldBlock(handle, url, source, mapContentType(type))
        }

        val lower = url.lowercase()
        val host = extractHost(lower) ?: return false
        val snap = kotlinEngine.get()
        for (rule in snap.exceptionsFor(host)) {
            if (rule.matches(lower, host, type, thirdParty)) return false
        }
        for (rule in snap.blocksFor(host)) {
            if (rule.matches(lower, host, type, thirdParty)) return true
        }
        return false
    }

    fun cosmeticsForUrl(url: String): NativeAdblock.CosmeticResources {
        val handle = nativeHandle.get()
        if (handle == 0L || !NativeAdblock.isLibraryLoaded) {
            return NativeAdblock.CosmeticResources.EMPTY
        }
        return NativeAdblock.cosmetics(handle, url)
    }

    private fun mapContentType(type: String): String = when (type) {
        "xhr" -> "xmlhttprequest"
        "stylesheet" -> "stylesheet"
        "subdocument" -> "subdocument"
        else -> type
    }

    private fun isEssentialYoutube(url: String): Boolean {
        val u = url.lowercase()

        // Known YouTube ad endpoints — still allow the filter engine to block these.
        if (u.contains("youtube.com/pagead") ||
            u.contains("youtube.com/ptracking") ||
            u.contains("youtube.com/api/stats/ads") ||
            u.contains("youtube.com/get_midroll") ||
            u.contains("youtube.com/pcs/") ||
            u.contains("youtube.com/pagead/") ||
            u.contains("/youtubei/v1/player/ad_")
        ) {
            return false
        }

        // Media / CDN / player bootstrap — never block or watch can stall ~15s+.
        if (u.contains(".googlevideo.com/")) return true
        if (u.contains("youtubei.googleapis.com")) return true
        if (u.contains("jnn-pa.googleapis.com")) return true
        if (u.contains("ytimg.com/")) return true
        if (u.contains("ggpht.com/")) return true
        if (u.contains("googleusercontent.com/")) return true
        if (u.contains("youtu.be/")) return true
        if (u.contains("youtube.com/")) return true
        if (u.contains("googleapis.com/youtube")) return true
        return false
    }

    /** Keep Google account / OAuth endpoints unblocked so YouTube login can persist. */
    private fun isEssentialGoogleAuth(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("accounts.google.")) return true
        if (u.contains("accounts.youtube.com")) return true
        if (u.contains("google.com/o/oauth2")) return true
        if (u.contains("google.com/signin")) return true
        if (u.contains("google.com/account")) return true
        if (u.contains("oauthaccountmanager.googleapis.com")) return true
        if (u.contains("gstatic.com/accounts")) return true
        if (u.contains("gstatic.com/og/_/js")) return true
        if (u.contains("ssl.gstatic.com/accounts")) return true
        if (u.contains("googleapis.com/oauth2")) return true
        if (u.contains("googleapis.com/identitytoolkit")) return true
        return false
    }

    private suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (loaded.get()) return
            val cache = File(context.cacheDir, "filters")
            if (!cache.exists()) cache.mkdirs()

            val lists = listOf(
                Triple(
                    "https://easylist.to/easylist/easylist.txt",
                    File(cache, "easylist.txt"),
                    "easylist.txt"
                ),
                Triple(
                    "https://easylist.to/easylist/easyprivacy.txt",
                    File(cache, "easyprivacy.txt"),
                    "easyprivacy.txt"
                )
            )

            for ((_, file, assetName) in lists) {
                seedFromAssetsIfNeeded(file, assetName)
            }
            for ((url, file, _) in lists) {
                try {
                    downloadIfStale(url, file)
                } catch (t: Throwable) {
                    Log.w(TAG, "Filter download failed for $url: ${t.message}")
                }
            }

            val combined = StringBuilder(1 shl 20)
            for ((_, file, _) in lists) {
                if (file.exists()) {
                    combined.append(file.readText())
                    combined.append('\n')
                }
            }
            val rulesText = combined.toString()

            // Prefer native engine
            if (NativeAdblock.isLibraryLoaded) {
                val old = nativeHandle.getAndSet(0L)
                if (old != 0L) NativeAdblock.destroy(old)
                val handle = NativeAdblock.create(rulesText)
                if (handle != 0L) {
                    nativeHandle.set(handle)
                    usingNativeEngine = true
                    loaded.set(true)
                    Log.i(TAG, "Native adblock-rust engine ready (${rulesText.length} chars of rules)")
                    return
                }
                Log.w(TAG, "Native engine create failed; using Kotlin fallback")
            }

            val blocks = ArrayList<Rule>(16_384)
            val exceptions = ArrayList<Rule>(1_024)
            for ((_, file, _) in lists) {
                if (file.exists()) parseIntoRules(file, blocks, exceptions)
            }
            kotlinEngine.set(KotlinEngine.build(blocks, exceptions))
            usingNativeEngine = false
            loaded.set(true)
            Log.i(TAG, "Kotlin fallback loaded ${blocks.size} block + ${exceptions.size} exception rules")
        }
    }

    private fun seedFromAssetsIfNeeded(file: File, assetName: String) {
        if (file.exists() && file.length() > 0) return
        try {
            context.assets.open(assetName).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not seed $assetName: ${t.message}")
        }
    }

    private fun downloadIfStale(src: String, dst: File) {
        val maxAgeMs = 24 * 60 * 60 * 1000L
        if (dst.exists() && dst.length() > 0 &&
            System.currentTimeMillis() - dst.lastModified() < maxAgeMs
        ) {
            return
        }

        val tmp = File(dst.parentFile, dst.name + ".tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(src).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OTube/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Filter download HTTP $code for $src")
                return
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < 64) {
                tmp.delete()
                return
            }
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn?.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun parseIntoRules(
        file: File,
        blocks: MutableList<Rule>,
        exceptions: MutableList<Rule>
    ) {
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("!") || line.startsWith("[")) return@forEachLine
            if (line.contains("##") || line.contains("#@#") || line.contains("#$#") ||
                line.contains("#%#") || line.contains("#?#")
            ) {
                return@forEachLine
            }

            val isException = line.startsWith("@@")
            val body = if (isException) line.removePrefix("@@") else line
            val rule = parseNetworkRule(body) ?: return@forEachLine
            if (isException) exceptions.add(rule) else blocks.add(rule)
        }
    }

    private fun parseNetworkRule(raw: String): Rule? {
        val dollar = findOptionsDelimiter(raw)
        val patternPart: String
        val optionsPart: String?
        if (dollar >= 0) {
            patternPart = raw.substring(0, dollar)
            optionsPart = raw.substring(dollar + 1)
        } else {
            patternPart = raw
            optionsPart = null
        }
        if (patternPart.isBlank()) return null

        val options = parseOptions(optionsPart)
        if (options.unsupportedOnly) return null

        return when {
            patternPart.startsWith("||") -> {
                val rest = patternPart.removePrefix("||")
                val anchorEnd = rest.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '?' }
                val host = if (anchorEnd >= 0) rest.substring(0, anchorEnd) else rest
                if (host.isBlank() || host.contains(' ') || host.contains('/')) return null
                val pathTail = if (anchorEnd >= 0) rest.substring(anchorEnd) else ""
                Rule(
                    hostAnchor = host.lowercase(),
                    pathPattern = normalizePattern(pathTail),
                    options = options
                )
            }
            else -> {
                val normalized = normalizePattern(
                    patternPart.removePrefix("|").removeSuffix("|")
                ) ?: return null
                if (normalized == "*") return null
                if (!normalized.contains('/') && !normalized.startsWith("*.")) return null
                if (normalized.length < 5) return null
                Rule(pattern = normalized, options = options)
            }
        }
    }

    private fun findOptionsDelimiter(raw: String): Int {
        val idx = raw.lastIndexOf('$')
        if (idx <= 0) return -1
        val after = raw.substring(idx + 1)
        if (after.isEmpty()) return -1
        if (after.any { it == '/' || it == ':' }) return -1
        return idx
    }

    private fun normalizePattern(p: String): String? {
        if (p.isBlank()) return null
        return p.lowercase().replace("^", "*")
    }

    private fun parseOptions(raw: String?): RuleOptions {
        if (raw.isNullOrBlank()) return RuleOptions()
        var thirdParty: Boolean? = null
        val types = mutableSetOf<String>()
        var hasTypeConstraint = false
        var sawUnsupported = false
        val includeDomains = mutableSetOf<String>()
        val excludeDomains = mutableSetOf<String>()

        for (token in raw.split(',')) {
            val opt = token.trim().lowercase()
            if (opt.isEmpty()) continue
            when {
                opt == "third-party" || opt == "3p" -> thirdParty = true
                opt == "~third-party" || opt == "~3p" || opt == "first-party" || opt == "1p" ->
                    thirdParty = false
                opt.startsWith("domain=") -> {
                    for (d in opt.removePrefix("domain=").split('|')) {
                        if (d.startsWith("~")) excludeDomains.add(d.removePrefix("~"))
                        else if (d.isNotBlank()) includeDomains.add(d)
                    }
                }
                opt in SUPPORTED_TYPES -> {
                    hasTypeConstraint = true
                    types.add(if (opt == "xhr") "xmlhttprequest" else opt)
                }
                opt.startsWith("~") && opt.removePrefix("~") in SUPPORTED_TYPES -> {
                    hasTypeConstraint = true
                }
                opt in UNSUPPORTED_OPTIONS ||
                    (opt.startsWith("~") && opt.removePrefix("~") in UNSUPPORTED_OPTIONS) -> {
                    sawUnsupported = true
                }
            }
        }

        if (sawUnsupported && !hasTypeConstraint && types.isEmpty() && thirdParty == null &&
            includeDomains.isEmpty() && excludeDomains.isEmpty()
        ) {
            return RuleOptions(unsupportedOnly = true)
        }

        return RuleOptions(
            thirdParty = thirdParty,
            resourceTypes = if (types.isNotEmpty()) types else emptySet(),
            includeDomains = includeDomains,
            excludeDomains = excludeDomains
        )
    }

    data class RuleOptions(
        val thirdParty: Boolean? = null,
        val resourceTypes: Set<String> = emptySet(),
        val includeDomains: Set<String> = emptySet(),
        val excludeDomains: Set<String> = emptySet(),
        val unsupportedOnly: Boolean = false
    )

    data class Rule(
        val hostAnchor: String? = null,
        val pathPattern: String? = null,
        val pattern: String? = null,
        val options: RuleOptions = RuleOptions()
    ) {
        fun matches(urlLower: String, requestHost: String, type: String, thirdParty: Boolean): Boolean {
            options.thirdParty?.let { required ->
                if (required != thirdParty) return false
            }
            if (options.resourceTypes.isNotEmpty()) {
                val mapped = if (type == "xhr") "xmlhttprequest" else type
                if (mapped !in options.resourceTypes) return false
            }
            if (options.includeDomains.isNotEmpty()) {
                if (options.includeDomains.none { domainMatches(requestHost, it) }) return false
            }
            if (options.excludeDomains.any { domainMatches(requestHost, it) }) return false

            hostAnchor?.let { anchor ->
                if (!hostMatchesAnchor(requestHost, anchor)) return false
                val path = pathPattern
                if (!path.isNullOrBlank() && path != "*" && !wildcardContains(urlLower, path)) {
                    return false
                }
                return true
            }

            pattern?.let { p -> return wildcardContains(urlLower, p) }
            return false
        }

        private fun hostMatchesAnchor(host: String, anchor: String): Boolean =
            host == anchor || host.endsWith(".$anchor")

        private fun domainMatches(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        private fun wildcardContains(text: String, pattern: String): Boolean {
            if (!pattern.contains('*')) return text.contains(pattern)
            val parts = pattern.split('*')
            var idx = 0
            for (part in parts) {
                if (part.isEmpty()) continue
                val found = text.indexOf(part, idx)
                if (found == -1) return false
                idx = found + part.length
            }
            return true
        }
    }

    class KotlinEngine(
        private val blockByHost: Map<String, List<Rule>>,
        private val exceptionByHost: Map<String, List<Rule>>,
        private val genericBlocks: List<Rule>,
        private val genericExceptions: List<Rule>
    ) {
        fun blocksFor(host: String): Sequence<Rule> = sequence {
            yieldAll(genericBlocks)
            for (key in hostKeys(host)) {
                blockByHost[key]?.let { yieldAll(it) }
            }
        }

        fun exceptionsFor(host: String): Sequence<Rule> = sequence {
            yieldAll(genericExceptions)
            for (key in hostKeys(host)) {
                exceptionByHost[key]?.let { yieldAll(it) }
            }
        }

        private fun hostKeys(host: String): List<String> {
            val parts = host.split('.')
            if (parts.size < 2) return listOf(host)
            val keys = ArrayList<String>(parts.size)
            for (i in 0 until parts.size - 1) {
                keys.add(parts.subList(i, parts.size).joinToString("."))
            }
            return keys
        }

        companion object {
            val EMPTY = KotlinEngine(emptyMap(), emptyMap(), emptyList(), emptyList())

            fun build(blocks: List<Rule>, exceptions: List<Rule>): KotlinEngine {
                val blockMap = HashMap<String, MutableList<Rule>>()
                val exceptionMap = HashMap<String, MutableList<Rule>>()
                val genericBlocks = ArrayList<Rule>()
                val genericExceptions = ArrayList<Rule>()

                fun index(
                    rule: Rule,
                    map: MutableMap<String, MutableList<Rule>>,
                    generic: MutableList<Rule>
                ) {
                    val host = rule.hostAnchor
                    if (host.isNullOrBlank()) generic.add(rule)
                    else map.getOrPut(host) { ArrayList() }.add(rule)
                }

                for (r in blocks) index(r, blockMap, genericBlocks)
                for (r in exceptions) index(r, exceptionMap, genericExceptions)
                return KotlinEngine(blockMap, exceptionMap, genericBlocks, genericExceptions)
            }
        }
    }

    companion object {
        private const val TAG = "FilterListManager"

        private val SUPPORTED_TYPES = setOf(
            "script", "image", "stylesheet", "xmlhttprequest", "xhr",
            "media", "font", "other", "subdocument", "ping", "websocket"
        )
        private val UNSUPPORTED_OPTIONS = setOf(
            "popup", "document", "elemhide", "generichide", "genericblock",
            "csp", "inline-script", "inline-font", "rewrite"
        )

        fun extractHost(urlLower: String): String? {
            val start = urlLower.indexOf("://").let { if (it >= 0) it + 3 else 0 }
            val endCandidates = listOf(
                urlLower.indexOf('/', start),
                urlLower.indexOf('?', start),
                urlLower.indexOf('#', start)
            ).filter { it >= 0 }
            val end = endCandidates.minOrNull() ?: urlLower.length
            if (end <= start) return null
            return urlLower.substring(start, end).substringBefore(':')
        }

        @Volatile
        private var instance: FilterListManager? = null

        fun getInstance(context: Context): FilterListManager =
            instance ?: synchronized(this) {
                instance ?: FilterListManager(context.applicationContext).also { instance = it }
            }
    }
}
