package com.tgmonitor.app.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tgmonitor.app.scraper.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.proxyStore by preferencesDataStore(name = "proxy")

/**
 * Keeps a small POOL of verified proxies. Only used for the public web-page scraping path
 * (HTTPS to t.me, so a proxy cannot read or alter the content). Never used for the Telegram account.
 */
class ProxyManager(context: Context, scope: CoroutineScope) : ProxyBackend {
    private val ds = context.applicationContext.proxyStore
    private val modeKey = stringPreferencesKey("mode")
    private val poolKey = stringPreferencesKey("pool")
    private val failedAtKey = longPreferencesKey("search_failed_at")
    private val probeKey = longPreferencesKey("direct_probe_at")
    private val blockedAtKey = longPreferencesKey("blocked_at")
    private val logKey = stringPreferencesKey("log")

    private val base = OkHttpClient.Builder().build()
    private val directClient = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val searchMutex = Mutex()
    private val logMutex = Mutex()

    private val _status = MutableStateFlow(ProxyStatus())
    val status: StateFlow<ProxyStatus> = _status

    /** Newest first. Shown in Settings so a failing background scan can be diagnosed. */
    val logs: Flow<List<String>> = ds.data.map { p -> p[logKey].orEmpty().split("\n").filter { it.isNotBlank() }.reversed() }

    init {
        scope.launch { refresh() }
    }

    // ---------- state ----------

    private suspend fun refresh() {
        val prefs = ds.data.first()
        val pool = parsePool(prefs[poolKey])
        _status.update { it.copy(mode = readMode(prefs[modeKey]), current = pool.firstOrNull()?.key, poolSize = pool.size) }
    }

    private fun readMode(s: String?): ProxyMode = ProxyMode.values().firstOrNull { it.name == s } ?: ProxyMode.AUTO

    private fun parsePool(s: String?): List<ProxyEndpoint> = s.orEmpty().split("\n").mapNotNull { ProxyEndpoint.fromKey(it) }

    private suspend fun readPool(): List<ProxyEndpoint> = parsePool(ds.data.first()[poolKey])

    private suspend fun writePool(list: List<ProxyEndpoint>) {
        ds.edit { prefs ->
            prefs.remove(poolKey)
            if (list.isNotEmpty()) prefs[poolKey] = list.joinToString("\n") { p -> p.key }
        }
        _status.update { it.copy(current = list.firstOrNull()?.key, poolSize = list.size) }
    }

    suspend fun log(message: String) {
        val line = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date()) + "  " + message
        logMutex.withLock {
            ds.edit { prefs ->
                val lines = prefs[logKey].orEmpty().split("\n").filter { it.isNotBlank() } + line
                prefs[logKey] = lines.takeLast(MAX_LOG_LINES).joinToString("\n")
            }
        }
    }

    override suspend fun mode(): ProxyMode = readMode(ds.data.first()[modeKey])

    suspend fun setMode(mode: ProxyMode) {
        ds.edit { it[modeKey] = mode.name }
        if (mode == ProxyMode.OFF) clear("أُوقف البروكسي")
        refresh()
    }

    // ---------- pool ----------

    override suspend fun current(): ProxyEndpoint? {
        val pool = readPool()
        if (pool.isEmpty()) return null
        // Return to the direct connection as soon as it works again (checked at most every 30 minutes).
        val now = System.currentTimeMillis()
        val lastProbe = ds.data.first()[probeKey] ?: 0L
        if (now - lastProbe > DIRECT_PROBE_EVERY_MS) {
            ds.edit { it[probeKey] = now }
            if (directWorks()) {
                writePool(emptyList())
                clearBlocked()
                log("عاد الاتصال المباشر للعمل، أُوقف البروكسي")
                return null
            }
            log("الاتصال المباشر ما زال محجوباً، يستمر البروكسي")
        }
        return pool.first()
    }

    override suspend fun invalidate(proxy: ProxyEndpoint) {
        val rest = readPool().filter { it != proxy }
        writePool(rest)
        log("سقط البروكسي ${proxy.key} (المتبقي ${rest.size})")
    }

    suspend fun clear(message: String? = null) {
        writePool(emptyList())
        clearBlocked()
        if (message != null) _status.update { it.copy(message = message) }
    }

    // ---------- block memory ----------

    override suspend fun knownBlocked(): Boolean =
        System.currentTimeMillis() - (ds.data.first()[blockedAtKey] ?: 0L) < BLOCK_MEMORY_MS

    override suspend fun markBlocked() {
        val wasKnown = knownBlocked()
        ds.edit { it[blockedAtKey] = System.currentTimeMillis() }
        if (!wasKnown) log("تأكد الحجب: تيليجرام لا يفتح مباشرة والإنترنت شغال")
    }

    private suspend fun clearBlocked() {
        ds.edit { it.remove(blockedAtKey) }
    }

    // ---------- probes ----------

    override suspend fun internetReachable(): Boolean {
        for (url in CONTROL_URLS) if (request(directClient, url) is FetchResult.Ok) return true
        return false
    }

    private suspend fun directWorks(): Boolean {
        val r = request(directClient, VALIDATION_URL)
        return r is FetchResult.Ok && r.html.contains("tgme_widget_message")
    }

    /**
     * Called once at the start of every scan run, BEFORE any channel is fetched, so the (slow) search
     * never happens in the middle of a channel and the whole run uses a ready pool.
     */
    suspend fun prepare() {
        if (mode() == ProxyMode.OFF) return
        if (current() != null) return
        if (directWorks()) {
            clearBlocked()
            return
        }
        if (!internetReachable()) {
            log("لا يوجد إنترنت الآن، لن أبحث عن بروكسي")
            return
        }
        markBlocked()
        findWorking()
    }

    override suspend fun fetchVia(proxy: ProxyEndpoint, url: String): FetchResult =
        request(clientFor(proxy, fast = false), url)

    // ---------- search ----------

    /** Automatic search (respects a 10-minute cool-down after a failed search). */
    override suspend fun findWorking(): ProxyEndpoint? = searchMutex.withLock { findLocked(force = false) }

    /** Manual search from Settings: ignores the cool-down. */
    suspend fun searchNow(): ProxyEndpoint? = searchMutex.withLock { findLocked(force = true) }

    private suspend fun findLocked(force: Boolean): ProxyEndpoint? {
        if (!force) {
            // Another caller may have filled the pool while we waited for the lock.
            val existing = readPool().firstOrNull()
            if (existing != null) return existing
            val failedAt = ds.data.first()[failedAtKey] ?: 0L
            if (System.currentTimeMillis() - failedAt < SEARCH_COOLDOWN_MS) return null
        }

        _status.update { it.copy(searching = true, message = "جارٍ البحث عن بروكسي شغّال...") }
        log("بدأ البحث عن بروكسي")
        val found = try {
            search()
        } finally {
            _status.update { it.copy(searching = false) }
        }
        if (found.isEmpty()) {
            ds.edit { it[failedAtKey] = System.currentTimeMillis() }
            _status.update { it.copy(message = "لم يُعثر على بروكسي شغّال الآن. سيُعاد البحث لاحقاً.") }
            log("فشل البحث: لا يوجد بروكسي شغّال")
            return null
        }
        writePool(found)
        ds.edit { it[probeKey] = System.currentTimeMillis(); it.remove(failedAtKey) }
        _status.update { it.copy(message = "جاهز: ${found.size} بروكسي") }
        log("وُجد ${found.size} بروكسي شغّال: ${found.first().key}")
        return found.first()
    }

    /** Validates candidates in parallel; returns as soon as a first winner appears (plus a short grace for more). */
    private suspend fun search(): List<ProxyEndpoint> {
        val candidates = loadCandidates()
        if (candidates.isEmpty()) {
            _status.update { it.copy(message = "تعذر تحميل قوائم البروكسي (قد تكون محجوبة أيضاً).") }
            log("تعذر تحميل قوائم البروكسي")
            return emptyList()
        }
        log("اختبار ${candidates.size} مرشحاً")
        return coroutineScope {
            val gate = Semaphore(PARALLEL)
            val results = Channel<ProxyEndpoint>(Channel.UNLIMITED)
            val jobs = candidates.map { c ->
                launch {
                    gate.withPermit {
                        if (validate(c)) results.trySend(c)
                    }
                }
            }
            launch {
                jobs.joinAll()
                results.close()
            }
            val found = ArrayList<ProxyEndpoint>()
            val first = results.receiveCatching().getOrNull()
            if (first != null) {
                found += first
                withTimeoutOrNull(GRACE_MS) {
                    while (found.size < POOL_SIZE) {
                        val next = results.receiveCatching().getOrNull() ?: break
                        found += next
                    }
                }
            }
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            found
        }
    }

    /** A candidate is good only if it returns a real Telegram channel page over verified HTTPS. */
    private suspend fun validate(p: ProxyEndpoint): Boolean {
        val r = request(clientFor(p, fast = true), VALIDATION_URL)
        return r is FetchResult.Ok && r.html.contains("tgme_widget_message")
    }

    /** The lists are sorted fastest-first and re-verified hourly, so test their heads, interleaved across sources. */
    private suspend fun loadCandidates(): List<ProxyEndpoint> {
        val lists = ArrayList<List<ProxyEndpoint>>()
        for ((url, type) in SOURCES) {
            val r = request(directClient, url)
            if (r is FetchResult.Ok) lists += ProxyListParser.parse(r.html, type, PER_LIST)
            if (lists.size >= 4 && lists.sumOf { it.size } >= MAX_CANDIDATES) break
        }
        val mixed = LinkedHashSet<ProxyEndpoint>()
        var i = 0
        while (mixed.size < MAX_CANDIDATES && lists.any { i < it.size }) {
            for (l in lists) if (i < l.size && mixed.size < MAX_CANDIDATES) mixed += l[i]
            i++
        }
        return mixed.toList()
    }

    // ---------- http ----------

    private fun clientFor(p: ProxyEndpoint, fast: Boolean): OkHttpClient {
        val java = Proxy(
            if (p.type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(p.host, p.port), // SOCKS: the proxy resolves t.me (bypasses DNS blocking)
        )
        return base.newBuilder().proxy(java).apply {
            if (fast) {
                connectTimeout(6, TimeUnit.SECONDS); readTimeout(12, TimeUnit.SECONDS); callTimeout(15, TimeUnit.SECONDS)
            } else {
                // Short on purpose: a dead proxy must fail fast so the pool can rotate.
                connectTimeout(8, TimeUnit.SECONDS); readTimeout(20, TimeUnit.SECONDS); callTimeout(30, TimeUnit.SECONDS)
            }
        }.build()
    }

    private suspend fun request(client: OkHttpClient, url: String): FetchResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) FetchResult.HttpError(resp.code, resp.header("Retry-After")?.toLongOrNull())
                else FetchResult.Ok(resp.body?.string().orEmpty(), resp.request.url.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            FetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    private companion object {
        const val VALIDATION_URL = "https://t.me/s/telegram"
        const val PER_LIST = 100
        const val MAX_CANDIDATES = 200
        const val PARALLEL = 20
        const val POOL_SIZE = 3
        const val GRACE_MS = 8_000L
        const val MAX_LOG_LINES = 40
        const val SEARCH_COOLDOWN_MS = 10L * 60_000L
        const val DIRECT_PROBE_EVERY_MS = 30L * 60_000L
        const val BLOCK_MEMORY_MS = 30L * 60_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        val CONTROL_URLS = listOf("https://connectivitycheck.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace")

        // Public lists (re-checked hourly by their maintainers), with CDN mirrors in case raw.githubusercontent.com is blocked.
        val SOURCES = listOf(
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt" to ProxyType.SOCKS5,
            "https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/socks5.txt" to ProxyType.SOCKS5,
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt" to ProxyType.HTTP,
            "https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/http.txt" to ProxyType.HTTP,
            "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt" to ProxyType.SOCKS5,
            "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt" to ProxyType.HTTP,
        )
    }
}
