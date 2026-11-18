package com.tgmonitor.app

import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.data.DeletionStatus
import com.tgmonitor.app.engine.AddChannelResult
import com.tgmonitor.app.engine.ChannelManager
import com.tgmonitor.app.engine.HtmlMessageSource
import com.tgmonitor.app.engine.NotificationKind
import com.tgmonitor.app.engine.ScanEngine
import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.testutil.FakeStore
import com.tgmonitor.app.testutil.FakeTelegram
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Env(batchSize: Int = 100) {
    val tg = FakeTelegram("testchan")
    val store = FakeStore()
    var now = 1_000L
    val engine = ScanEngine(store, tg, clock = { now++ }, batchSize = batchSize)

    suspend fun channel(): Long = store.insertChannel(
        ChannelEntity(username = "testchan", title = "testchan", url = "https://t.me/testchan", dateAdded = now)
    )!!

    fun ch(id: Long) = store.channels.first { it.id == id }
    fun msg(id: Long) = store.messages.first { it.messageId == id }
}

class ScanEngineTest {

    @Test fun baselineIsSilentAndSetsCheckpoint() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel()
        val o = e.engine.scanChannel(id)
        assertTrue(o.success)
        assertEquals(5, e.store.messages.size)
        assertTrue(e.store.events.isEmpty())
        assertTrue(o.notifications.isEmpty())
        assertEquals(5L, e.ch(id).lastKnownMessageId)
        assertTrue(e.ch(id).baselineSet)
        assertEquals("Fake Channel", e.ch(id).title)
    }

    @Test fun multipleSimultaneousPostsAllDetected() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.addRange(6..12)
        val o = e.engine.scanChannel(id)
        assertEquals(7, o.newPosts)
        assertEquals((6L..12L).toList(), e.store.events.filter { it.changeType == ChangeType.NEW_POST }.map { it.messageId })
        assertEquals(7, o.notifications.count { it.kind == NotificationKind.NEW_POST })
        assertEquals(12L, e.ch(id).lastKnownMessageId)
    }

    @Test fun repeatedScansNeverDuplicate() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.addRange(6..8)
        repeat(4) { e.engine.scanChannel(id) }
        assertEquals(8, e.store.messages.size)
        assertEquals(8, e.store.messages.map { it.messageId }.toSet().size)
        assertEquals(3, e.store.events.count { it.changeType == ChangeType.NEW_POST })
        assertEquals(8, e.store.versions.size)
    }

    @Test fun paginationFindsEveryPostWhenMoreThanOnePageIsNew() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.addRange(6..50)
        val o = e.engine.scanChannel(id)
        assertEquals(45, o.newPosts)
        assertEquals(50, e.store.messages.size)
        assertTrue(e.tg.requests.any { it.contains("before=") })
    }

    @Test fun batchesCommitInChunksAndKeepEverything() = runTest {
        val e = Env(batchSize = 3); e.tg.addRange(1..2)
        val id = e.channel(); e.engine.scanChannel(id)
        val before = e.store.commitCount
        e.tg.addRange(3..12) // 10 new posts -> 4 chunks
        e.engine.scanChannel(id)
        assertTrue(e.store.commitCount - before >= 4)
        assertEquals(12, e.store.messages.size)
        assertEquals(12L, e.ch(id).lastKnownMessageId)
    }

    @Test fun networkOutageKeepsCheckpointAndResumesLater() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.addRange(6..8)
        e.tg.pageOverride = FetchResult.NetworkError("offline")
        val failed = e.engine.scanChannel(id)
        assertFalse(failed.success)
        assertEquals(ChannelStatus.NETWORK_ERROR, e.ch(id).status)
        assertEquals(5L, e.ch(id).lastKnownMessageId)
        assertEquals(5, e.store.messages.size)
        e.tg.pageOverride = null
        val ok = e.engine.scanChannel(id)
        assertEquals(3, ok.newPosts)
        assertEquals(ChannelStatus.OK, e.ch(id).status)
    }

    @Test fun resumesAfterRestartWithNewEngineInstance() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.addRange(6..9) // posts while the phone was off
        val restarted = ScanEngine(e.store, e.tg, clock = { e.now++ })
        val o = restarted.scanChannel(id)
        assertEquals(4, o.newPosts)
        assertEquals(9, e.store.messages.size)
    }

    @Test fun postPublishedDuringScanIsCaughtByRecheck() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.add(6)
        e.tg.afterNextPageFetch = { e.tg.add(7) }
        val o = e.engine.scanChannel(id)
        assertEquals(2, o.newPosts)
        assertEquals(7L, e.ch(id).lastKnownMessageId)
    }

    @Test fun mediaOnlyPostAdvancesCheckpointButIsNotStored() = runTest {
        val e = Env(); e.tg.addRange(1..3)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.add(4, hasText = false)
        val o = e.engine.scanChannel(id)
        assertEquals(0, o.newPosts)
        assertEquals(3, e.store.messages.size)
        assertEquals(4L, e.ch(id).lastKnownMessageId)
    }

    @Test fun textEditKeepsOriginalAndCreatesVersion() = runTest {
        val e = Env(); e.tg.add(1, "old text"); e.tg.add(2)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.editText(1, "new text here")
        val o = e.engine.scanChannel(id)
        val m = e.msg(1)
        assertEquals("old text", m.originalText)
        assertEquals("new text here", m.currentText)
        assertEquals(2, m.versionCount)
        assertEquals(listOf("old text", "new text here"), e.store.versions.filter { it.messageRowId == m.id }.sortedBy { it.versionNumber }.map { it.content })
        val ev = e.store.events.single { it.changeType == ChangeType.TEXT_EDITED }
        assertEquals("old text", ev.oldContent); assertEquals("new text here", ev.newContent)
        assertEquals(1, o.edits)
        assertEquals(NotificationKind.EDIT, o.notifications.single().kind)
        // unchanged afterwards -> no extra version
        e.engine.scanChannel(id)
        assertEquals(2, e.msg(1).versionCount)
    }

    @Test fun linkEditDetected() = runTest {
        val e = Env(); e.tg.add(1, "see", links = listOf("https://a.com")); e.tg.add(2)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.editLinks(1, listOf("https://b.com"))
        e.engine.scanChannel(id)
        val ev = e.store.events.single { it.changeType == ChangeType.LINKS_EDITED }
        assertEquals("LINK_MODIFIED", ev.detail)
        assertTrue(ev.oldContent!!.contains("https://a.com")); assertTrue(ev.newContent!!.contains("https://b.com"))
        assertEquals("https://b.com", e.msg(1).currentLinks); assertEquals("https://a.com", e.msg(1).originalLinks)
    }

    @Test fun deletionConfirmedByEmbedCheck() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.delete(3)
        val o = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.CONFIRMED_DELETED, e.msg(3).deletionStatus)
        assertNotNull(e.msg(3).deletionDetectedAt)
        assertEquals(1, o.confirmedDeletions)
        assertEquals(NotificationKind.DELETION_CONFIRMED, o.notifications.single().kind)
        assertEquals("post 3", e.msg(3).currentText) // history retained
    }

    @Test fun inconclusiveCheckGoesUnknownThenSuspectedAfterThreeScans() = runTest {
        val e = Env(); e.tg.addRange(1..5); e.tg.embedInconclusive = true
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.delete(3)
        val first = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.UNKNOWN, e.msg(3).deletionStatus)
        assertTrue(first.notifications.isEmpty())
        val second = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.UNKNOWN, e.msg(3).deletionStatus)
        assertTrue(second.notifications.isEmpty())
        val third = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.SUSPECTED_DELETED, e.msg(3).deletionStatus)
        assertEquals(NotificationKind.DELETION_SUSPECTED, third.notifications.single().kind)
        assertTrue(e.store.events.none { it.changeType == ChangeType.DELETION_CONFIRMED })
        assertTrue(e.engine.scanChannel(id).notifications.isEmpty()) // no repeat alerts
    }

    @Test fun staleCachedPageDoesNotCreateFalseEdit() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        val stale = e.tg.snapshot()                 // page with the old text
        e.tg.editText(1, "edited text")
        e.tg.scriptedPages += e.tg.snapshot()       // first look: edited
        e.tg.scriptedPages += stale                 // confirmation look: old text again (disagrees)
        val o = e.engine.scanChannel(id)
        assertEquals(0, o.edits)
        assertEquals("post 1", e.msg(1).currentText)
        assertTrue(e.store.events.isEmpty())
        // a real edit (both looks agree) is still recorded on the next scan
        assertEquals(1, e.engine.scanChannel(id).edits)
        assertEquals("edited text", e.msg(1).currentText)
    }

    @Test fun transientlyMissingPostIsNotADeletion() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.hidden += 3
        val partial = e.tg.snapshot()
        e.tg.hidden -= 3
        e.tg.scriptedPages += partial               // first look lacks post 3, confirmation look has it
        val o = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.ACTIVE, e.msg(3).deletionStatus)
        assertTrue(e.store.events.isEmpty())
        assertTrue(o.notifications.isEmpty())
        assertTrue(e.tg.requests.none { it.contains("embed=1") })
    }

    @Test fun newestPostsMissingFromAStalePageAreNotJudgedDeleted() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.hidden += 5
        val stale = e.tg.snapshot()                 // lacks the newest post only
        e.tg.hidden -= 5
        e.tg.scriptedPages += stale
        e.engine.scanChannel(id)
        assertEquals(DeletionStatus.ACTIVE, e.msg(5).deletionStatus)
        assertTrue(e.store.events.isEmpty())
        assertTrue(e.tg.requests.none { it.contains("embed=1") })
    }

    @Test fun olderPostsBelowTheCheckpointAreNeverReportedAsNew() = runTest {
        val e = Env(); e.tg.pageSize = 5; e.tg.addRange(41..60)
        val id = e.channel(); e.engine.scanChannel(id)      // baseline only saw 56..60
        e.tg.pageSize = 20; e.tg.addRange(61..65)
        val o = e.engine.scanChannel(id)                    // page now reaches back to 46
        assertEquals(5, o.newPosts)
        assertEquals((61L..65L).toList(), e.store.events.filter { it.changeType == ChangeType.NEW_POST }.map { it.messageId })
        assertEquals(5, o.notifications.count { it.kind == NotificationKind.NEW_POST })
        assertEquals(20, e.store.messages.size)             // 46..55 stored silently
    }

    @Test fun accountSourceIsUsedOnlyForChannelsThatAskForIt() = runTest {
        val html = FakeTelegram("testchan"); html.previewDisabled = true
        val account = FakeTelegram("testchan"); account.addRange(1..4)
        val store = FakeStore()
        var useAccount = false
        val engine = ScanEngine(
            store, html, clock = { 1L },
            accountSource = HtmlMessageSource(account), useAccount = { useAccount },
        )
        val id = store.insertChannel(ChannelEntity(username = "testchan", title = "t", url = "u", dateAdded = 0))!!
        val blocked = engine.scanChannel(id)
        assertEquals(ChannelStatus.PREVIEW_DISABLED, blocked.channelStatus)
        assertTrue(account.requests.isEmpty())

        useAccount = true
        val ok = engine.scanChannel(id)
        assertTrue(ok.success)
        assertEquals(4, store.messages.size)
        assertEquals(ChannelStatus.OK, store.channels[0].status)
    }

    @Test fun postHiddenFromPreviewButStillExistingStaysActive() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.hidden += 3
        val o = e.engine.scanChannel(id)
        assertEquals(DeletionStatus.ACTIVE, e.msg(3).deletionStatus)
        assertTrue(o.notifications.isEmpty())
    }

    @Test fun connectionAndParseErrorsNeverMarkDeletions() = runTest {
        val e = Env(); e.tg.addRange(1..5)
        val id = e.channel(); e.engine.scanChannel(id)
        for (r in listOf(
            FetchResult.HttpError(503), FetchResult.HttpError(403), FetchResult.NetworkError("x"),
            FetchResult.Ok("<html><body>x</body></html>", "https://t.me/s/testchan"),
        )) {
            e.tg.pageOverride = r
            e.engine.scanChannel(id)
        }
        assertTrue(e.store.messages.all { it.deletionStatus == DeletionStatus.ACTIVE })
        assertTrue(e.store.events.isEmpty())
    }

    @Test fun repeatedParseFailuresNotifyOnceAtThreshold() = runTest {
        val e = Env(); e.tg.addRange(1..3)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.pageOverride = FetchResult.Ok("<html><body>x</body></html>", "https://t.me/s/testchan")
        assertTrue(e.engine.scanChannel(id).notifications.isEmpty())
        assertTrue(e.engine.scanChannel(id).notifications.isEmpty())
        val third = e.engine.scanChannel(id)
        assertEquals(NotificationKind.SCAN_FAILED, third.notifications.single().kind)
        assertEquals(ChannelStatus.PARSE_FAILURE, e.ch(id).status)
        assertTrue(e.engine.scanChannel(id).notifications.isEmpty())
    }

    @Test fun rateLimitIsReported() = runTest {
        val e = Env(); e.tg.addRange(1..3)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.pageOverride = FetchResult.HttpError(429)
        val o = e.engine.scanChannel(id)
        assertTrue(o.rateLimited)
        assertEquals(ChannelStatus.RATE_LIMITED, e.ch(id).status)
    }

    @Test fun previewDisabledIsFlaggedAndNotifiedOnce() = runTest {
        val e = Env(); e.tg.previewDisabled = true
        val id = e.channel()
        val first = e.engine.scanChannel(id)
        assertEquals(ChannelStatus.PREVIEW_DISABLED, e.ch(id).status)
        assertEquals(NotificationKind.PREVIEW_DISABLED, first.notifications.single().kind)
        assertTrue(e.engine.scanChannel(id).notifications.isEmpty())
        assertTrue(e.store.messages.isEmpty())
    }

    @Test fun scanHistoryIsRecorded() = runTest {
        val e = Env(); e.tg.addRange(1..3)
        val id = e.channel(); e.engine.scanChannel(id)
        e.tg.pageOverride = FetchResult.HttpError(503); e.engine.scanChannel(id)
        assertEquals(listOf("SUCCESS", "FAILED"), e.store.scans.map { it.status })
        assertEquals(3, e.store.scans[0].postsScanned)
        assertNotNull(e.store.scans[1].errorMessage)
    }
}

class ChannelManagerTest {
    private fun manager(env: Env) = ChannelManager(env.store, env.engine, clock = { env.now })

    @Test fun addsChannelWithSilentBaseline() = runTest {
        val e = Env(); e.tg.addRange(1..4)
        val r = manager(e).add("https://t.me/testchan") as AddChannelResult.Added
        assertFalse(r.previewDisabled)
        assertEquals(1, e.store.channels.size)
        assertEquals(4, e.store.messages.size)
        assertTrue(e.store.events.isEmpty())
        assertEquals(4L, e.store.channels[0].lastKnownMessageId)
    }

    @Test fun duplicateIsRejectedInAnyForm() = runTest {
        val e = Env(); e.tg.addRange(1..2)
        val m = manager(e)
        m.add("testchan")
        assertTrue(m.add("@TestChan") === AddChannelResult.Duplicate)
        assertTrue(m.add("t.me/s/testchan") === AddChannelResult.Duplicate)
        assertEquals(1, e.store.channels.size)
    }

    @Test fun invalidInputRejected() = runTest {
        val e = Env()
        assertTrue(manager(e).add("not a channel!") === AddChannelResult.InvalidInput)
        assertTrue(e.store.channels.isEmpty())
    }

    @Test fun unknownChannelIsNotKept() = runTest {
        val e = Env(); e.tg.notFound = true
        assertTrue(manager(e).add("testchan") === AddChannelResult.NotFound)
        assertTrue(e.store.channels.isEmpty())
    }

    @Test fun previewDisabledChannelIsKeptAndFlagged() = runTest {
        val e = Env(); e.tg.previewDisabled = true
        val r = manager(e).add("testchan") as AddChannelResult.Added
        assertTrue(r.previewDisabled)
        assertEquals(ChannelStatus.PREVIEW_DISABLED, e.store.channels[0].status)
    }

    @Test fun pauseResumeAndRemove() = runTest {
        val e = Env(); e.tg.addRange(1..2)
        val m = manager(e); m.add("testchan")
        val id = e.store.channels[0].id
        m.setMonitoring(id, false)
        assertTrue(e.store.getEnabledChannelsStalestFirst().isEmpty())
        m.setMonitoring(id, true)
        assertEquals(1, e.store.getEnabledChannelsStalestFirst().size)
        m.remove(id)
        assertTrue(e.store.channels.isEmpty()); assertTrue(e.store.messages.isEmpty()); assertTrue(e.store.versions.isEmpty())
    }
}
