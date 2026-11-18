package com.tgmonitor.app.proxy

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tgmonitor.app.scraper.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.proxyStore by preferencesDataStore(name = "proxy")

/**
 * Finds and remembers a working proxy. Only used for the public web-page scraping path
 * (HTTPS to t.me, so the proxy cannot read or alter the content). Never used for the Telegram account.
 */
class ProxyManager(context: Context, scope: CoroutineScope) : ProxyBackend {
    private val ds = context.applicationContext.proxyStore
    private val modeKey = stringPreferencesKey("mode")
    private val currentKey = stringPreferencesKey("current")
    private val failedAtKey = longPreferencesKey("search_failed_at")
    private val probeKey = longPreferencesKey("direct_probe_at")

    private val base = OkHttpClient.Builder().build()
    private val directClient = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val searchMutex = Mutex()

    private val _status = MutableStateFlow(ProxyStatus())
    val status: StateFlow<ProxyStatus> = _status

    init {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        val p = ds.data.first()
        _status.update { it.copy(mode = readMode(p[modeKey]), current = p[currentKey]) }
    }

    private fun readMode(s: String?): ProxyMode = ProxyMode.values().firstOrNull { it.name == s } ?: ProxyMode.AUTO

    override suspend fun mode(): ProxyMode = readMode(ds.data.first()[modeKey])

    suspend fun setMode(mode: ProxyMode) {
        ds.edit { it[modeKey] = mode.name }
        if (mode == ProxyMode.OFF) clear()
        refresh()
    }

    override suspend fun current(): ProxyEndpoint? {
        val prefs = ds.data.first()
        val proxy = prefs[currentKey]?.let { ProxyEndpoint.fromKey(it) } ?: return null
        // Return to the direct connection as soon as it works again (checked at most every 30 minutes).
        val now = System.currentTimeMillis()
        if (now - (prefs[probeKey] ?: 0L) > DIRECT_PROBE_EVERY_MS) {
            ds.edit { it[probeKey] = now }
            if (directWorks()) {
                clear("عاد الاتصال المباشر للعمل، أُوقف البروكسي")
                return null
            }
        }
        return proxy
    }

    override suspend fun invalidate(proxy: ProxyEndpoint) {
        if (ds.data.first()[currentKey] == proxy.key) clear("توقف البروكسي ${proxy.key}")
    }

    suspend fun clear(message: String? = null) {
        ds.edit { it.remove(currentKey) }
        _status.update { it.copy(current = null, message = message ?: it.message) }
    }

    override suspend fun internetReachable(): Boolean {
        for (url in CONTROL_URLS) if (request(directClient, url) is FetchResult.Ok) return true
        return false
    }

    private suspend fun directWorks(): Boolean {
        val r = request(directClient, VALIDATION_URL)
        return r is FetchResult.Ok && r.html.contains("tgme_widget_message")
    }

    override suspend fun fetchVia(proxy: ProxyEndpoint, url: String): FetchResult =
        request(clientFor(proxy, fast = false), url)

    /** Automatic search (respects a 10-minute cool-down after a failed search). */
    override suspend fun findWorking(): ProxyEndpoint? = findInternal(force = false)

    /** Manual search from Settings: ignores the cool-down. */
    suspend fun searchNow(): ProxyEndpoint? = findInternal(force = true)

    private suspend fun findInternal(force: Boolean): ProxyEndpoint? = searchMutex.withLock { findLocked(force) }

    private suspend fun findLocked(force: Boolean): ProxyEndpoint? {
        if (!force) {
            // Another caller may have found one while we waited for the lock.
            val existing = ds.data.first()[currentKey]?.let { ProxyEndpoint.fromKey(it) }
            if (existing != null) return existing
            val failedAt = ds.data.first()[failedAtKey] ?: 0L
            if (System.currentTimeMillis() - failedAt < SEARCH_COOLDOWN_MS) return null
        }

        _status.update { it.copy(searching = true, message = "جارٍ البحث عن بروكسي شغّال...") }
        val found = try {
            search()
        } finally {
            _status.update { it.copy(searching = false) }
        }
        if (found == null) {
            ds.edit { it[failedAtKey] = System.currentTimeMillis() }
            _status.update { it.copy(message = "لم يُعثر على بروكسي شغّال الآن. سيُعاد البحث لاحقاً.") }
            return null
        }
        ds.edit { it[currentKey] = found.key; it[probeKey] = System.currentTimeMillis() }
        _status.update { it.copy(current = found.key, message = "يعمل عبر بروكسي: ${found.key}") }
        return found
    }

    private suspend fun search(): ProxyEndpoint? {
        val candidates = loadCandidates()
        if (candidates.isEmpty()) {
            _status.update { it.copy(message = "تعذر تحميل قوائم البروكسي (قد تكون محجوبة أيضاً).") }
            return null
        }
        return coroutineScope {
            val gate = Semaphore(PARALLEL)
            val winner = CompletableDeferred<ProxyEndpoint?>()
            val jobs = candidates.map { c ->
                launch {
                    gate.withPermit {
                        if (!winner.isCompleted && validate(c)) winner.complete(c)
                    }
                }
            }
            launch {
                jobs.joinAll()
                winner.complete(null)
            }
            val result = winner.await()
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            result
        }
    }

    /** A candidate is good only if it returns a real Telegram channel page over verified HTTPS. */
    private suspend fun validate(p: ProxyEndpoint): Boolean {
        val r = request(clientFor(p, fast = true), VALIDATION_URL)
        return r is FetchResult.Ok && r.html.contains("tgme_widget_message")
    }

    private suspend fun loadCandidates(): List<ProxyEndpoint> {
        val socks = ArrayList<ProxyEndpoint>()
        val http = ArrayList<ProxyEndpoint>()
        for ((url, type) in SOURCES) {
            if (socks.size >= 300 && http.size >= 300) break
            val r = request(directClient, url)
            if (r is FetchResult.Ok) {
                val list = ProxyListParser.parse(r.html, type, 600)
                if (type == ProxyType.SOCKS5) socks += list else http += list
            }
        }
        val half = MAX_CANDIDATES / 2
        return (socks.distinct().shuffled().take(half) + http.distinct().shuffled().take(half)).shuffled()
    }

    private fun clientFor(p: ProxyEndpoint, fast: Boolean): OkHttpClient {
        val java = Proxy(
            if (p.type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(p.host, p.port), // SOCKS: the proxy resolves t.me (bypasses DNS blocking)
        )
        return base.newBuilder().proxy(java).apply {
            if (fast) {
                connectTimeout(6, TimeUnit.SECONDS); readTimeout(8, TimeUnit.SECONDS); callTimeout(10, TimeUnit.SECONDS)
            } else {
                connectTimeout(15, TimeUnit.SECONDS); readTimeout(30, TimeUnit.SECONDS); callTimeout(45, TimeUnit.SECONDS)
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
        const val MAX_CANDIDATES = 80
        const val PARALLEL = 10
        const val SEARCH_COOLDOWN_MS = 10L * 60_000L
        const val DIRECT_PROBE_EVERY_MS = 30L * 60_000L
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
