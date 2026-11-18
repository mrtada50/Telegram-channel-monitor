package com.tgmonitor.app

import com.tgmonitor.app.proxy.BlockHeuristics
import com.tgmonitor.app.proxy.ProxyAwareFetcher
import com.tgmonitor.app.proxy.ProxyBackend
import com.tgmonitor.app.proxy.ProxyEndpoint
import com.tgmonitor.app.proxy.ProxyListParser
import com.tgmonitor.app.proxy.ProxyMode
import com.tgmonitor.app.proxy.ProxyType
import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.scraper.PageFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyListParserTest {
    @Test fun parsesValidLinesAndSkipsTheRest() {
        val text = """
            # comment
            1.2.3.4:1080

            bad line
            5.6.7.8:80
            300.1.1.1:80
            user:pw@1.1.1.1:80
            socks5://9.9.9.9:1080
            socks4://8.8.8.8:1080
            http://7.7.7.7:3128
            1.2.3.4:1080
            2.2.2.2:70000
        """.trimIndent()
        val list = ProxyListParser.parse(text, ProxyType.SOCKS5, 100)
        assertEquals(
            listOf(
                ProxyEndpoint(ProxyType.SOCKS5, "1.2.3.4", 1080),
                ProxyEndpoint(ProxyType.SOCKS5, "5.6.7.8", 80),
                ProxyEndpoint(ProxyType.SOCKS5, "9.9.9.9", 1080),
                ProxyEndpoint(ProxyType.HTTP, "7.7.7.7", 3128),
            ),
            list,
        )
    }

    @Test fun respectsLimitAndRoundTripsKeys() {
        assertEquals(2, ProxyListParser.parse("1.1.1.1:80\n2.2.2.2:80\n3.3.3.3:80", ProxyType.HTTP, 2).size)
        val p = ProxyEndpoint(ProxyType.SOCKS5, "1.2.3.4", 1080)
        assertEquals("socks5://1.2.3.4:1080", p.key)
        assertEquals(p, ProxyEndpoint.fromKey(p.key))
        assertNull(ProxyEndpoint.fromKey("nonsense"))
    }
}

class BlockHeuristicsTest {
    private val goodPage = FetchResult.Ok("<div class=\"tgme_widget_message\">x</div>", "https://t.me/s/x")

    @Test fun networkErrorsAndIspPagesLookBlocked() {
        assertTrue(BlockHeuristics.looksBlocked(FetchResult.NetworkError("timeout")))
        assertTrue(BlockHeuristics.looksBlocked(FetchResult.Ok("<html>blocked by ISP</html>", "https://t.me/s/x")))
        assertTrue(BlockHeuristics.looksBlocked(FetchResult.Ok("telegram tgme", "http://blocked.example/page")))
        assertTrue(BlockHeuristics.looksBlocked(FetchResult.HttpError(451)))
        assertTrue(BlockHeuristics.looksBlocked(FetchResult.HttpError(403)))
    }

    @Test fun normalAnswersAreNotBlocking() {
        assertFalse(BlockHeuristics.looksBlocked(goodPage))
        assertFalse(BlockHeuristics.looksBlocked(FetchResult.HttpError(404)))
        assertFalse(BlockHeuristics.looksBlocked(FetchResult.HttpError(429)))
        assertTrue(BlockHeuristics.proxyFailed(FetchResult.HttpError(407)))
        assertFalse(BlockHeuristics.proxyFailed(goodPage))
    }
}

class ProxyAwareFetcherTest {
    private val goodPage = FetchResult.Ok("<div class=\"tgme_widget_message\">x</div>", "https://t.me/s/x")
    private val proxy = ProxyEndpoint(ProxyType.SOCKS5, "1.2.3.4", 1080)

    private class Direct(var result: FetchResult) : PageFetcher {
        var calls = 0
        override suspend fun fetch(url: String): FetchResult { calls++; return result }
    }

    private class Backend : ProxyBackend {
        var mode = ProxyMode.AUTO
        var current: ProxyEndpoint? = null
        var reachable = true
        var found: ProxyEndpoint? = null
        var via: FetchResult = FetchResult.Ok("<div class=\"tgme_widget_message\">x</div>", "https://t.me/s/x")
        var searches = 0
        var viaCalls = 0
        val invalidated = mutableListOf<ProxyEndpoint>()
        override suspend fun mode() = mode
        override suspend fun current() = current
        override suspend fun fetchVia(proxy: ProxyEndpoint, url: String): FetchResult { viaCalls++; return via }
        override suspend fun internetReachable() = reachable
        override suspend fun findWorking(): ProxyEndpoint? { searches++; return found }
        override suspend fun invalidate(proxy: ProxyEndpoint) { invalidated += proxy; if (current == proxy) current = null }
    }

    @Test fun offModeNeverTouchesProxies() = runTest {
        val d = Direct(FetchResult.NetworkError("x")); val b = Backend().apply { mode = ProxyMode.OFF; found = proxy }
        ProxyAwareFetcher(d, b).fetch("u")
        assertEquals(0, b.searches); assertEquals(0, b.viaCalls)
    }

    @Test fun workingDirectConnectionNeverSearches() = runTest {
        val d = Direct(goodPage); val b = Backend().apply { found = proxy }
        assertSame(goodPage, ProxyAwareFetcher(d, b).fetch("u"))
        assertEquals(0, b.searches)
    }

    @Test fun blockedConnectionFindsAProxyAndRetriesThroughIt() = runTest {
        val d = Direct(FetchResult.NetworkError("timeout")); val b = Backend().apply { found = proxy }
        val r = ProxyAwareFetcher(d, b).fetch("u")
        assertSame(b.via, r)
        assertEquals(1, b.searches); assertEquals(1, b.viaCalls)
    }

    @Test fun ispBlockPageAlsoTriggersTheSearch() = runTest {
        val d = Direct(FetchResult.Ok("<html>blocked</html>", "http://blocked.example/")); val b = Backend().apply { found = proxy }
        assertSame(b.via, ProxyAwareFetcher(d, b).fetch("u"))
    }

    @Test fun offlinePhoneIsNotTreatedAsBlocked() = runTest {
        val err = FetchResult.NetworkError("no route")
        val d = Direct(err); val b = Backend().apply { reachable = false; found = proxy }
        assertSame(err, ProxyAwareFetcher(d, b).fetch("u"))
        assertEquals(0, b.searches)
    }

    @Test fun notFoundAndRateLimitAreNotBlocking() = runTest {
        val b = Backend().apply { found = proxy }
        for (code in listOf(404, 429)) {
            val r = FetchResult.HttpError(code)
            assertSame(r, ProxyAwareFetcher(Direct(r), b).fetch("u"))
        }
        assertEquals(0, b.searches)
    }

    @Test fun activeProxyIsUsedFirst() = runTest {
        val d = Direct(goodPage); val b = Backend().apply { current = proxy }
        assertSame(b.via, ProxyAwareFetcher(d, b).fetch("u"))
        assertEquals(0, d.calls)
    }

    @Test fun deadProxyIsForgottenAndDirectIsTried() = runTest {
        val d = Direct(goodPage)
        val b = Backend().apply { current = proxy; via = FetchResult.NetworkError("dead") }
        assertSame(goodPage, ProxyAwareFetcher(d, b).fetch("u"))
        assertEquals(listOf(proxy), b.invalidated)
        assertNull(b.current)
    }

    @Test fun noWorkingProxyKeepsTheOriginalError() = runTest {
        val err = FetchResult.NetworkError("timeout")
        val d = Direct(err); val b = Backend().apply { found = null }
        assertSame(err, ProxyAwareFetcher(d, b).fetch("u"))
        assertEquals(1, b.searches)
    }

    @Test fun proxyThatAlsoFailsKeepsTheOriginalError() = runTest {
        val err = FetchResult.NetworkError("timeout")
        val d = Direct(err); val b = Backend().apply { found = proxy; via = FetchResult.NetworkError("proxy dead") }
        assertSame(err, ProxyAwareFetcher(d, b).fetch("u"))
    }
}
