package com.tgmonitor.app

import com.tgmonitor.app.engine.ChannelInputParser
import com.tgmonitor.app.engine.IntervalPolicy
import com.tgmonitor.app.scraper.EmbedState
import com.tgmonitor.app.scraper.PageParse
import com.tgmonitor.app.scraper.TelegramPageParser
import com.tgmonitor.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelInputParserTest {
    @Test fun acceptsAllForms() {
        listOf("testchan", "@TestChan", "https://t.me/testchan", "t.me/testchan", "http://telegram.me/TestChan",
            "https://t.me/s/testchan", "https://t.me/testchan/123?single").forEach {
            assertEquals(it, "testchan", ChannelInputParser.parse(it))
        }
    }

    @Test fun rejectsInvalid() {
        listOf("", "   ", "abc", "has space", "https://t.me/joinchat/AAAA", "https://t.me/+invite", "https://t.me/c/123/4",
            "https://example.com/testchan", "1abcde", "a".repeat(40)).forEach { assertNull(it, ChannelInputParser.parse(it)) }
    }
}

class IntervalPolicyTest {
    @Test fun validation() {
        assertTrue(IntervalPolicy.validate("15") is IntervalPolicy.Validation.Valid)
        assertTrue(IntervalPolicy.validate(" 90 ") is IntervalPolicy.Validation.Valid)
        assertTrue(IntervalPolicy.validate("14") is IntervalPolicy.Validation.Invalid)
        assertTrue(IntervalPolicy.validate("0") is IntervalPolicy.Validation.Invalid)
        assertTrue(IntervalPolicy.validate("abc") is IntervalPolicy.Validation.Invalid)
        assertTrue(IntervalPolicy.validate("999999") is IntervalPolicy.Validation.Invalid)
        assertEquals(15, IntervalPolicy.DEFAULT)
        assertTrue(IntervalPolicy.PRESETS.all { it >= IntervalPolicy.MIN })
    }
}

class TelegramPageParserTest {
    private val url = "https://t.me/s/testchan"

    @Test fun parsesMessages() {
        val r = TelegramPageParser.parseChannelPage(Fixtures.load("channel_page.html"), url, "testchan") as PageParse.Messages
        assertEquals("Test Channel", r.title)
        assertEquals(listOf(101L, 102L, 103L), r.messages.map { it.id })
        val m = r.messages[0]
        assertEquals("Hello world\nSecond line with link and #tag", m.text)
        assertEquals(listOf("https://example.com/a?x=1"), m.links) // hashtag link ignored
        assertEquals(1714557600000L, m.publishedAt)
        assertEquals("https://t.me/testchan/101", m.url)
        assertFalse(m.edited)
    }

    @Test fun editedMarkerAndReplyTextIgnored() {
        val r = TelegramPageParser.parseChannelPage(Fixtures.load("channel_page.html"), url, "testchan") as PageParse.Messages
        assertTrue(r.messages[1].edited)
        assertEquals("Edited post", r.messages[1].text) // not the quoted reply text
    }

    @Test fun mediaOnlyPostHasNoContent() {
        val r = TelegramPageParser.parseChannelPage(Fixtures.load("channel_page.html"), url, "testchan") as PageParse.Messages
        assertFalse(r.messages[2].hasContent)
    }

    @Test fun previewDisabledNotFoundEmptyBroken() {
        assertTrue(TelegramPageParser.parseChannelPage(Fixtures.load("preview_disabled.html"), "https://t.me/x", "x") === PageParse.PreviewDisabled)
        assertTrue(TelegramPageParser.parseChannelPage(Fixtures.load("not_found.html"), "https://t.me/x", "x") === PageParse.NotAChannel)
        assertTrue(TelegramPageParser.parseChannelPage(Fixtures.load("empty_channel.html"), url, "x") is PageParse.Empty)
        assertTrue(TelegramPageParser.parseChannelPage(Fixtures.load("broken.html"), url, "x") is PageParse.Broken)
    }

    @Test fun embedClassification() {
        assertEquals(EmbedState.EXISTS, TelegramPageParser.classifyEmbed(Fixtures.load("embed_exists.html")))
        assertEquals(EmbedState.GONE, TelegramPageParser.classifyEmbed(Fixtures.load("embed_gone.html")))
        assertEquals(EmbedState.INCONCLUSIVE, TelegramPageParser.classifyEmbed("<html></html>"))
    }
}
