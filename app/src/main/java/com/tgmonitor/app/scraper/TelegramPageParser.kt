package com.tgmonitor.app.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.OffsetDateTime

data class ParsedMessage(
    val id: Long,
    val text: String,
    val links: List<String>,
    val publishedAt: Long?,
    val url: String,
    val edited: Boolean,
) {
    /** Media-only posts have no text/links and are outside the monitoring scope. */
    val hasContent: Boolean get() = text.isNotEmpty() || links.isNotEmpty()
}

sealed interface PageParse {
    class Messages(val title: String?, val messages: List<ParsedMessage>) : PageParse
    class Empty(val title: String?) : PageParse
    data object PreviewDisabled : PageParse
    data object NotAChannel : PageParse
    class Broken(val reason: String) : PageParse
}

enum class EmbedState { EXISTS, GONE, INCONCLUSIVE }

object TextNormalizer {
    fun normalize(raw: String): String =
        raw.replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}

/**
 * Parses https://t.me/s/{username}. The selectors follow the widget markup Telegram serves today.
 * Any structure this parser does not recognise is reported as Broken, never as an empty channel.
 */
object TelegramPageParser {
    private val SUBSCRIBERS = Regex("subscriber|member|مشترك|عضو", RegexOption.IGNORE_CASE)
    private val EDITED = Regex("edited|معدل|تم التعديل", RegexOption.IGNORE_CASE)

    fun parseChannelPage(html: String, finalUrl: String, username: String): PageParse {
        val doc = Jsoup.parse(html, "https://t.me/s/$username")
        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val elements = doc.select("div.tgme_widget_message[data-post]")
        if (elements.isNotEmpty()) {
            val messages = elements.mapNotNull { parseMessage(it, username) }
            if (messages.isEmpty()) return PageParse.Broken("تعذر قراءة معرّفات المنشورات")
            return PageParse.Messages(title, messages)
        }
        if (doc.selectFirst(".tgme_channel_info") != null) return PageParse.Empty(title)

        val redirectedAway = !finalUrl.contains("/s/")
        if (redirectedAway || doc.selectFirst(".tgme_page") != null) {
            val extra = doc.selectFirst(".tgme_page_extra")?.text().orEmpty()
            return if (SUBSCRIBERS.containsMatchIn(extra)) PageParse.PreviewDisabled else PageParse.NotAChannel
        }
        return PageParse.Broken("بنية صفحة تيليجرام غير معروفة")
    }

    /** Classifies https://t.me/{user}/{id}?embed=1 used to double-check a missing post. */
    fun classifyEmbed(html: String): EmbedState {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst(".tgme_widget_message_error") != null) return EmbedState.GONE
        if (doc.selectFirst("div.tgme_widget_message[data-post], .tgme_widget_message_text") != null) return EmbedState.EXISTS
        return EmbedState.INCONCLUSIVE
    }

    private fun parseMessage(el: Element, username: String): ParsedMessage? {
        val id = el.attr("data-post").substringAfterLast('/').toLongOrNull() ?: return null
        // js-message_text is the post body; replies/quotes use js-message_reply_text.
        val textEl = el.selectFirst("div.tgme_widget_message_text.js-message_text")
        val text = if (textEl == null) "" else TextNormalizer.normalize(extract(textEl))
        val links = textEl?.select("a[href]")?.mapNotNull { a ->
            val raw = a.attr("href").trim()
            if (raw.isEmpty() || raw.startsWith("?") || raw.startsWith("#")) null
            else a.absUrl("href").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }?.distinct() ?: emptyList()

        val dateEl = el.selectFirst("a.tgme_widget_message_date")
        val url = dateEl?.absUrl("href")?.takeIf { it.isNotEmpty() } ?: "https://t.me/$username/$id"
        val published = dateEl?.selectFirst("time[datetime]")?.attr("datetime")?.let {
            try {
                OffsetDateTime.parse(it).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
        val edited = el.selectFirst(".tgme_widget_message_meta")?.text()?.let { EDITED.containsMatchIn(it) } ?: false
        return ParsedMessage(id, text, links, published, url, edited)
    }

    private fun extract(root: Node): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is Element -> if (node.tagName() == "br") sb.append('\n') else node.childNodes().forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return sb.toString()
    }
}
