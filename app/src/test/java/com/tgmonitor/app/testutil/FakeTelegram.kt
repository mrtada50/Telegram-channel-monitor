package com.tgmonitor.app.testutil

import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.scraper.PageFetcher

/** Simulates t.me/s/{user} (20 posts per page, ?before= pagination) and the ?embed=1 post check. */
class FakeTelegram(private val username: String) : PageFetcher {
    class Post(var text: String, var links: List<String> = emptyList(), var hasText: Boolean = true)

    val posts = sortedMapOf<Long, Post>()
    val hidden = mutableSetOf<Long>()
    var pageOverride: FetchResult? = null
    var previewDisabled = false
    var notFound = false
    var embedInconclusive = false
    var afterNextPageFetch: (() -> Unit)? = null
    var pageSize = 20
    val requests = mutableListOf<String>()

    fun add(id: Long, text: String = "post $id", links: List<String> = emptyList(), hasText: Boolean = true) {
        posts[id] = Post(text, links, hasText)
    }

    fun addRange(range: IntRange) = range.forEach { add(it.toLong()) }

    fun editText(id: Long, text: String) { posts.getValue(id).text = text }
    fun editLinks(id: Long, links: List<String>) { posts.getValue(id).links = links }
    fun delete(id: Long) { posts.remove(id) }

    override suspend fun fetch(url: String): FetchResult {
        requests += url
        if (url.contains("/s/")) {
            pageOverride?.let { return it }
            if (previewDisabled) return FetchResult.Ok(Fixtures.load("preview_disabled.html"), "https://t.me/$username")
            if (notFound) return FetchResult.Ok(Fixtures.load("not_found.html"), "https://t.me/$username")
            val before = Regex("before=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            val ids = posts.keys.filter { it !in hidden && (before == null || it < before) }
            val result = FetchResult.Ok(pageHtml(ids.takeLast(pageSize)), "https://t.me/s/$username")
            afterNextPageFetch?.let { hook -> afterNextPageFetch = null; hook() }
            return result
        }
        if (url.contains("embed=1")) {
            val id = Regex("/(\\d+)\\?embed").find(url)!!.groupValues[1].toLong()
            val html = when {
                embedInconclusive -> "<html><body></body></html>"
                id in posts -> Fixtures.load("embed_exists.html")
                else -> Fixtures.load("embed_gone.html")
            }
            return FetchResult.Ok(html, url)
        }
        return FetchResult.HttpError(404)
    }

    private fun pageHtml(ids: List<Long>): String {
        val sb = StringBuilder(
            "<html><head><meta property=\"og:title\" content=\"Fake Channel\"></head><body>" +
                "<div class=\"tgme_channel_info\"><div class=\"tgme_channel_info_header_title\"><span>Fake Channel</span></div></div>" +
                "<section class=\"tgme_channel_history\">"
        )
        ids.forEach { sb.append(postHtml(it, posts.getValue(it))) }
        return sb.append("</section></body></html>").toString()
    }

    private fun postHtml(id: Long, p: Post): String {
        val body = if (p.hasText) {
            val links = p.links.joinToString("") { " <a href=\"$it\">link</a>" }
            "<div class=\"tgme_widget_message_text js-message_text\" dir=\"auto\">${escape(p.text)}$links</div>"
        } else "<a class=\"tgme_widget_message_photo_wrap\" href=\"x\"></a>"
        return "<div class=\"tgme_widget_message_wrap\"><div class=\"tgme_widget_message js-widget_message\" data-post=\"$username/$id\">" +
            "<div class=\"tgme_widget_message_bubble\">$body<div class=\"tgme_widget_message_footer\"><div class=\"tgme_widget_message_info\">" +
            "<span class=\"tgme_widget_message_meta\"><a class=\"tgme_widget_message_date\" href=\"https://t.me/$username/$id\">" +
            "<time datetime=\"2024-05-01T10:00:00+00:00\" class=\"time\">10:00</time></a></span></div></div></div></div></div>"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
