package com.tgmonitor.app.proxy

import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.scraper.PageFetcher

enum class ProxyType { HTTP, SOCKS5 }

/** AUTO: connect directly, and only if Telegram looks blocked, find a working proxy. OFF: never use a proxy. */
enum class ProxyMode { AUTO, OFF }

data class ProxyEndpoint(val type: ProxyType, val host: String, val port: Int) {
    val key: String get() = "${type.name.lowercase()}://$host:$port"

    companion object {
        fun fromKey(key: String): ProxyEndpoint? {
            val m = Regex("^(http|socks5)://([^:/]+):(\\d{1,5})$").find(key.trim()) ?: return null
            val port = m.groupValues[3].toInt()
            if (port !in 1..65535) return null
            val type = if (m.groupValues[1] == "socks5") ProxyType.SOCKS5 else ProxyType.HTTP
            return ProxyEndpoint(type, m.groupValues[2], port)
        }
    }
}

data class ProxyStatus(
    val mode: ProxyMode = ProxyMode.AUTO,
    val current: String? = null,
    val poolSize: Int = 0,
    val searching: Boolean = false,
    val message: String? = null,
)

/** Everything the fetcher needs from the proxy machinery (a fake in tests, ProxyManager in the app). */
interface ProxyBackend {
    suspend fun mode(): ProxyMode
    suspend fun current(): ProxyEndpoint?
    suspend fun fetchVia(proxy: ProxyEndpoint, url: String): FetchResult

    /** Control probe: can we reach a neutral host directly? (separates "no internet" from "Telegram blocked"). */
    suspend fun internetReachable(): Boolean

    /** Search public lists, validate candidates against real Telegram pages, remember the winners (a pool). */
    suspend fun findWorking(): ProxyEndpoint?
    suspend fun invalidate(proxy: ProxyEndpoint)

    /** A block was confirmed recently: skip the slow direct attempt and go straight to the proxy pool. */
    suspend fun knownBlocked(): Boolean
    suspend fun markBlocked()
}

object ProxyListParser {
    private val LINE = Regex("^(?:(socks5|socks4|http|https)://)?(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})$", RegexOption.IGNORE_CASE)

    /** Parses `host:port` / `scheme://host:port` lines. Skips credentials, socks4, bad IPs and duplicates. */
    fun parse(text: String, defaultType: ProxyType, limit: Int): List<ProxyEndpoint> {
        val out = LinkedHashSet<ProxyEndpoint>()
        for (raw in text.lineSequence()) {
            if (out.size >= limit) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.contains('@')) continue
            val m = LINE.find(line) ?: continue
            val scheme = m.groupValues[1].lowercase()
            val type = when (scheme) {
                "" -> defaultType
                "socks5" -> ProxyType.SOCKS5
                "http", "https" -> ProxyType.HTTP
                else -> continue // socks4 is not supported
            }
            val host = m.groupValues[2]
            if (host.split('.').any { it.toInt() > 255 }) continue
            val port = m.groupValues[3].toInt()
            if (port !in 1..65535) continue
            out += ProxyEndpoint(type, host, port)
        }
        return out.toList()
    }
}

object BlockHeuristics {
    private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.org")

    /** A 200 page that is not Telegram's (ISP block page, captive portal, redirect to another host). */
    fun isBlockPage(ok: FetchResult.Ok): Boolean {
        val host = ok.finalUrl.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        if (host !in TELEGRAM_HOSTS && !host.endsWith(".t.me")) return true
        val html = ok.html
        return !html.contains("tgme", ignoreCase = true) && !html.contains("telegram", ignoreCase = true)
    }

    /** Direct connection looks blocked. 404 and 429 are NOT blocking signals. */
    fun looksBlocked(r: FetchResult): Boolean = when (r) {
        is FetchResult.NetworkError -> true
        is FetchResult.HttpError -> r.code == 403 || r.code == 451
        is FetchResult.Ok -> isBlockPage(r)
    }

    /** A proxy attempt that did not give us a usable Telegram response. */
    fun proxyFailed(r: FetchResult): Boolean =
        looksBlocked(r) || (r is FetchResult.HttpError && (r.code == 407 || r.code in 502..504))
}

/**
 * Pooled proxies first (rotating instantly when one is dead), then direct unless a block was confirmed
 * recently, then a fresh proxy search. A dead proxy never costs a long direct timeout.
 */
class ProxyAwareFetcher(
    private val direct: PageFetcher,
    private val backend: ProxyBackend,
) : PageFetcher {

    override suspend fun fetch(url: String): FetchResult {
        if (backend.mode() == ProxyMode.OFF) return direct.fetch(url)

        // 1) Pooled proxies.
        var tries = 0
        while (tries < MAX_POOL_TRIES) {
            val active = backend.current() ?: break
            tries++
            val r = backend.fetchVia(active, url)
            if (!BlockHeuristics.proxyFailed(r)) return r
            backend.invalidate(active)
        }

        // 2) Direct, unless the block was confirmed recently.
        val knownBlocked = backend.knownBlocked()
        val first: FetchResult =
            if (knownBlocked) FetchResult.NetworkError("تيليجرام محجوب ولا يوجد بروكسي جاهز")
            else direct.fetch(url)
        if (!BlockHeuristics.looksBlocked(first)) return first
        if (!knownBlocked && !backend.internetReachable()) return first // offline, not blocked
        backend.markBlocked()

        // 3) A fresh proxy search, then retry through the winner.
        val found = backend.findWorking() ?: return first
        val viaProxy = backend.fetchVia(found, url)
        return if (BlockHeuristics.proxyFailed(viaProxy)) first else viaProxy
    }

    private companion object {
        const val MAX_POOL_TRIES = 3
    }
}
