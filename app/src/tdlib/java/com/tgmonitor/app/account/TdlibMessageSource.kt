package com.tgmonitor.app.account

import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.engine.DeletionCheck
import com.tgmonitor.app.engine.MessageSource
import com.tgmonitor.app.engine.SourcePage
import com.tgmonitor.app.scraper.ParsedMessage
import com.tgmonitor.app.scraper.TextNormalizer
import org.drinkless.tdlib.TdApi

/** Reads channel messages through the logged-in account, one channel at a time with human-like pauses. */
internal class TdlibMessageSource(
    private val session: TdlibSession,
    private val pacer: Pacer,
) : MessageSource {

    private val chatIds = HashMap<String, Long>()
    private var chatTitle: String? = null

    private suspend fun resolve(username: String): Pair<Long, String?>? {
        chatIds[username]?.let { return it to chatTitle }
        val r = session.call(TdApi.SearchPublicChat().apply { this.username = username })
        if (r !is TdApi.Chat) return null
        val isChannel = (r.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        if (!isChannel) return null
        chatIds[username] = r.id
        chatTitle = r.title
        return r.id to r.title
    }

    override suspend fun page(ch: ChannelEntity, before: Long?, pagination: Boolean): SourcePage {
        pacer.before(ch.username)
        val resolved = when (val r = session.call(TdApi.SearchPublicChat().apply { username = ch.username })) {
            is TdApi.Chat -> r
            is TdApi.Error -> return failure(r)
            else -> return SourcePage.Failed(ChannelStatus.PARSE_FAILURE, "استجابة غير متوقعة من TDLib")
        }
        if ((resolved.type as? TdApi.ChatTypeSupergroup)?.isChannel != true) {
            return SourcePage.Failed(ChannelStatus.NOT_FOUND, "هذا المعرّف ليس قناة")
        }
        chatIds[ch.username] = resolved.id

        val found = LinkedHashMap<Long, ParsedMessage>()
        var fromId = if (before == null) 0L else before shl 20
        var tries = 0
        // TDLib may return fewer messages than asked (especially on the first call), so ask again.
        while (found.size < PAGE_SIZE && tries < MAX_TRIES) {
            tries++
            val res = session.call(
                TdApi.GetChatHistory().apply {
                    chatId = resolved.id
                    fromMessageId = fromId
                    offset = 0
                    limit = PAGE_SIZE
                    onlyLocal = false
                }
            )
            if (res is TdApi.Error) return failure(res)
            val messages = (res as? TdApi.Messages)?.messages ?: break
            if (messages.isEmpty()) break
            val before0 = found.size
            for (m in messages) toParsed(m, ch.username)?.let { found[it.id] = it }
            if (found.size == before0) break
            fromId = messages.last().id
            pacer.before(ch.username)
        }

        if (found.isEmpty() && before == null && ch.baselineSet && ch.lastKnownMessageId > 0) {
            return SourcePage.Failed(ChannelStatus.PARSE_FAILURE, "TDLib أعاد صفحة فارغة لقناة فيها منشورات")
        }
        return SourcePage.Ok(resolved.title, found.values.toList())
    }

    override suspend fun check(ch: ChannelEntity, messageId: Long): DeletionCheck {
        pacer.before(ch.username)
        val chat = resolve(ch.username) ?: return DeletionCheck.ERROR
        val res = session.call(TdApi.GetMessage().apply { chatId = chat.first; this.messageId = messageId shl 20 })
        return when {
            res is TdApi.Message -> DeletionCheck.EXISTS
            res is TdApi.Error && (res.code == 404 || res.message.contains("not found", ignoreCase = true)) -> DeletionCheck.GONE
            else -> DeletionCheck.ERROR
        }
    }

    private fun toParsed(m: TdApi.Message, username: String): ParsedMessage? {
        val serverId = m.id shr 20
        if (serverId <= 0L) return null
        val formatted: TdApi.FormattedText? = when (val c = m.content) {
            is TdApi.MessageText -> c.text
            is TdApi.MessagePhoto -> c.caption
            is TdApi.MessageVideo -> c.caption
            is TdApi.MessageDocument -> c.caption
            is TdApi.MessageAnimation -> c.caption
            is TdApi.MessageAudio -> c.caption
            is TdApi.MessageVoiceNote -> c.caption
            else -> null
        }
        val full = formatted?.text.orEmpty()
        val links = LinkedHashSet<String>()
        formatted?.entities?.forEach { e ->
            when (val t = e.type) {
                is TdApi.TextEntityTypeTextUrl -> links += t.url
                is TdApi.TextEntityTypeUrl -> {
                    val end = e.offset + e.length
                    if (e.offset >= 0 && end <= full.length) {
                        val raw = full.substring(e.offset, end)
                        links += if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
                    }
                }
                else -> Unit
            }
        }
        return ParsedMessage(
            id = serverId,
            text = TextNormalizer.normalize(full),
            links = links.filter { it.startsWith("http://") || it.startsWith("https://") },
            publishedAt = m.date * 1000L,
            url = "https://t.me/$username/$serverId",
            edited = m.editDate > 0,
        )
    }

    private fun failure(e: TdApi.Error): SourcePage.Failed {
        val m = e.message.orEmpty()
        return when {
            e.code == 429 -> SourcePage.Failed(ChannelStatus.RATE_LIMITED, "429: $m", rateLimited = true)
            m.contains("USERNAME_NOT_OCCUPIED", true) || m.contains("USERNAME_INVALID", true) ->
                SourcePage.Failed(ChannelStatus.NOT_FOUND, "القناة غير موجودة")
            m.contains("CHANNEL_PRIVATE", true) || e.code == 401 || e.code == 403 ->
                SourcePage.Failed(ChannelStatus.ACCESS_DENIED, "قد تحتاج الانضمام للقناة أو تسجيل الدخول: $m")
            e.code >= 500 -> SourcePage.Failed(ChannelStatus.SERVER_ERROR, "$m")
            else -> SourcePage.Failed(ChannelStatus.NETWORK_ERROR, "${e.code}: $m")
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_TRIES = 4
    }
}
