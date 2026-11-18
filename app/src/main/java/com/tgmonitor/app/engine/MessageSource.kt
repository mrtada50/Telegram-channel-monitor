package com.tgmonitor.app.engine

import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.scraper.EmbedState
import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.scraper.PageFetcher
import com.tgmonitor.app.scraper.PageParse
import com.tgmonitor.app.scraper.ParsedMessage
import com.tgmonitor.app.scraper.TelegramPageParser

sealed interface SourcePage {
    class Ok(val title: String?, val messages: List<ParsedMessage>) : SourcePage
    class Failed(val channelStatus: String?, val message: String, val rateLimited: Boolean = false) : SourcePage
}

enum class DeletionCheck { EXISTS, GONE, INCONCLUSIVE, ERROR }

/** Where messages come from: public web pages (default) or a logged-in Telegram account. */
interface MessageSource {
    /** Newest page when [before] is null, otherwise messages around/older than [before]. */
    suspend fun page(ch: ChannelEntity, before: Long?, pagination: Boolean): SourcePage

    suspend fun check(ch: ChannelEntity, messageId: Long): DeletionCheck
}

class HtmlMessageSource(private val fetcher: PageFetcher) : MessageSource {

    override suspend fun page(ch: ChannelEntity, before: Long?, pagination: Boolean): SourcePage {
        val url = if (before == null) "https://t.me/s/${ch.username}" else "https://t.me/s/${ch.username}?before=$before"
        return when (val r = fetcher.fetch(url)) {
            is FetchResult.NetworkError ->
                SourcePage.Failed(ChannelStatus.NETWORK_ERROR, "خطأ شبكة: ${r.message}")
            is FetchResult.HttpError -> when {
                r.code == 404 -> SourcePage.Failed(ChannelStatus.NOT_FOUND, "HTTP 404")
                r.code == 403 -> SourcePage.Failed(ChannelStatus.ACCESS_DENIED, "HTTP 403")
                r.code == 429 -> SourcePage.Failed(ChannelStatus.RATE_LIMITED, "HTTP 429 (تقييد من الخادم)", rateLimited = true)
                r.code >= 500 -> SourcePage.Failed(ChannelStatus.SERVER_ERROR, "HTTP ${r.code}")
                else -> SourcePage.Failed(ChannelStatus.NETWORK_ERROR, "HTTP ${r.code}")
            }
            is FetchResult.Ok -> when (val p = TelegramPageParser.parseChannelPage(r.html, r.finalUrl, ch.username)) {
                is PageParse.Messages -> SourcePage.Ok(p.title, p.messages)
                is PageParse.Empty ->
                    // A previously active channel suddenly "empty" is a parse problem, not an empty channel.
                    if (pagination || !(ch.baselineSet && ch.lastKnownMessageId > 0)) SourcePage.Ok(p.title, emptyList())
                    else SourcePage.Failed(ChannelStatus.PARSE_FAILURE, "صفحة فارغة غير متوقعة لقناة فيها منشورات")
                PageParse.PreviewDisabled ->
                    SourcePage.Failed(ChannelStatus.PREVIEW_DISABLED, "المعاينة العامة موقفة؛ القراءة تتطلب تسجيل دخول")
                PageParse.NotAChannel ->
                    SourcePage.Failed(ChannelStatus.NOT_FOUND, "القناة غير موجودة أو ليست قناة عامة")
                is PageParse.Broken -> SourcePage.Failed(ChannelStatus.PARSE_FAILURE, p.reason)
            }
        }
    }

    override suspend fun check(ch: ChannelEntity, messageId: Long): DeletionCheck =
        when (val r = fetcher.fetch("https://t.me/${ch.username}/$messageId?embed=1&mode=tme")) {
            is FetchResult.Ok -> when (TelegramPageParser.classifyEmbed(r.html)) {
                EmbedState.EXISTS -> DeletionCheck.EXISTS
                EmbedState.GONE -> DeletionCheck.GONE
                EmbedState.INCONCLUSIVE -> DeletionCheck.INCONCLUSIVE
            }
            else -> DeletionCheck.ERROR
        }
}
