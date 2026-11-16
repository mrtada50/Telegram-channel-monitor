package com.tgmonitor.app.engine

import com.tgmonitor.app.data.ChangeEventEntity
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelScanState
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.data.DeletionStatus
import com.tgmonitor.app.data.MessageEntity
import com.tgmonitor.app.data.MessageVersionEntity
import com.tgmonitor.app.data.MonitorStore
import com.tgmonitor.app.data.NewItem
import com.tgmonitor.app.data.ScanCommit
import com.tgmonitor.app.data.VerificationStatus
import com.tgmonitor.app.scraper.EmbedState
import com.tgmonitor.app.scraper.FetchResult
import com.tgmonitor.app.scraper.PageFetcher
import com.tgmonitor.app.scraper.PageParse
import com.tgmonitor.app.scraper.ParsedMessage
import com.tgmonitor.app.scraper.TelegramPageParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Scans one channel: finds every new post since the last committed checkpoint (with pagination),
 * detects edits, and verifies deletions. The checkpoint only advances inside the same transaction
 * that stores the posts, so an unsaved post is never skipped.
 */
class ScanEngine(
    private val store: MonitorStore,
    private val fetcher: PageFetcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxPages: Int = 100,
    private val batchSize: Int = 100,
    private val maxVerifications: Int = 10,
    private val pageDelayMs: Long = 0L,
) {
    private class Accumulator {
        var posts = 0
        var newPosts = 0
        var edits = 0
        var suspected = 0
        var confirmed = 0
        val notifications = mutableListOf<ScanNotification>()
    }

    private class Failure(val channelStatus: String?, val message: String, val rateLimited: Boolean = false)

    private sealed interface PageOutcome {
        class Ok(val title: String?, val messages: List<ParsedMessage>) : PageOutcome
        class Failed(val failure: Failure) : PageOutcome
    }

    private sealed interface PassResult {
        class Done(val needsRecheck: Boolean) : PassResult
        class Failed(val failure: Failure) : PassResult
    }

    private enum class Verify { EXISTS, GONE, INCONCLUSIVE, ERROR }

    suspend fun scanChannel(channelId: Long): ScanOutcome {
        store.getChannel(channelId) ?: return ScanOutcome(status = "FAILED", error = "القناة غير موجودة")
        val scanId = store.startScan(channelId, clock())
        val acc = Accumulator()
        var failure: Failure? = null
        try {
            var pass = 0
            while (pass < MAX_PASSES) {
                pass++
                when (val r = runPass(channelId, acc)) {
                    is PassResult.Failed -> {
                        // A failure on a later (re-check) pass keeps the data already committed.
                        if (pass == 1) failure = r.failure
                        break
                    }
                    is PassResult.Done -> if (!r.needsRecheck) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failure(null, "خطأ تخزين/داخلي: ${e.javaClass.simpleName}: ${e.message}")
        }

        val f = failure
        if (f != null) return handleFailure(channelId, scanId, f, acc)
        store.finishScan(scanId, clock(), "SUCCESS", acc.posts, null)
        return ScanOutcome(
            status = "SUCCESS", newPosts = acc.newPosts, edits = acc.edits,
            suspectedDeletions = acc.suspected, confirmedDeletions = acc.confirmed,
            postsScanned = acc.posts, notifications = acc.notifications, channelStatus = ChannelStatus.OK,
        )
    }

    private suspend fun handleFailure(channelId: Long, scanId: Long, f: Failure, acc: Accumulator): ScanOutcome {
        val notes = mutableListOf<ScanNotification>()
        val now = clock()
        try {
            val ch = store.getChannel(channelId)
            if (ch != null) {
                val failures = ch.consecutiveFailures + 1
                val kind = when {
                    f.channelStatus == ChannelStatus.PREVIEW_DISABLED ->
                        if (ch.status != ChannelStatus.PREVIEW_DISABLED) NotificationKind.PREVIEW_DISABLED else null
                    f.channelStatus == ChannelStatus.NOT_FOUND ->
                        if (ch.status != ChannelStatus.NOT_FOUND) NotificationKind.CHANNEL_UNREACHABLE else null
                    f.channelStatus == null -> NotificationKind.SCAN_FAILED
                    failures == FAILURE_NOTIFY_THRESHOLD -> NotificationKind.SCAN_FAILED
                    else -> null
                }
                if (kind != null) notes += ScanNotification(kind, ch.id, ch.title, null, now, f.message)
                if (f.channelStatus != null) {
                    store.updateScanState(
                        channelId,
                        ChannelScanState(status = f.channelStatus, consecutiveFailures = failures, lastError = f.message),
                    )
                }
            }
            store.finishScan(scanId, now, "FAILED", acc.posts, f.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage itself is failing; the outcome below still reports the original error.
        }
        return ScanOutcome(
            status = "FAILED", postsScanned = acc.posts, notifications = notes,
            rateLimited = f.rateLimited, error = f.message, channelStatus = f.channelStatus,
        )
    }

    private suspend fun runPass(channelId: Long, acc: Accumulator): PassResult {
        val ch = store.getChannel(channelId) ?: return PassResult.Failed(Failure(null, "القناة حُذفت أثناء الفحص"))
        val isBaseline = !ch.baselineSet
        val canPaginate = ch.baselineSet && ch.lastKnownMessageId > 0

        val first = when (val p = fetchPage(ch, null, false)) {
            is PageOutcome.Failed -> return PassResult.Failed(p.failure)
            is PageOutcome.Ok -> p
        }
        val collected = HashMap<Long, ParsedMessage>()
        first.messages.forEach { collected[it.id] = it }
        var pages = 1
        var cursor: Long? = collected.keys.minOrNull()
        // Walk backwards until we reach already-checkpointed territory (never stop at "latest post only").
        while (canPaginate && cursor != null && cursor > ch.lastKnownMessageId && pages < maxPages) {
            val before: Long = cursor
            if (pageDelayMs > 0) delay(pageDelayMs)
            val p = when (val r = fetchPage(ch, before, true)) {
                is PageOutcome.Failed -> return PassResult.Failed(r.failure) // abort: a gap must not be committed
                is PageOutcome.Ok -> r
            }
            val older = p.messages.filter { it.id < before }
            if (older.isEmpty()) break
            older.forEach { collected[it.id] = it }
            pages++
            cursor = older.minOf { it.id }
        }

        val all = collected.values.sortedBy { it.id }
        acc.posts += all.size
        val now = clock()
        val title = first.title?.takeIf { it.isNotBlank() }
        val channelTitle = title ?: ch.title
        val maxId = all.lastOrNull()?.id ?: 0L
        val minId = all.firstOrNull()?.id ?: 0L
        val existing = if (all.isEmpty()) emptyList() else store.getMessagesFrom(ch.id, minId)
        val existingById = existing.associateBy { it.messageId }

        val newItems = mutableListOf<NewItem>()
        val updates = mutableListOf<MessageEntity>()
        val versions = mutableListOf<MessageVersionEntity>()
        val events = mutableListOf<ChangeEventEntity>()
        val notes = mutableListOf<ScanNotification>()
        var edits = 0
        var suspected = 0
        var confirmed = 0

        for (pm in all) {
            val ex = existingById[pm.id]
            if (ex == null) {
                if (!pm.hasContent) continue
                val event = if (isBaseline) null else ChangeEventEntity(
                    channelId = ch.id, messageId = pm.id, changeType = ChangeType.NEW_POST,
                    newContent = ContentFormat.display(pm.text, pm.links), detectedAt = now,
                    verificationStatus = VerificationStatus.NOT_APPLICABLE,
                )
                newItems += NewItem(newMessage(ch.id, pm, now), event)
                if (!isBaseline) notes += ScanNotification(NotificationKind.NEW_POST, ch.id, channelTitle, pm.id, now, null)
                continue
            }
            var updated = ex
            val newHash = ContentHasher.hash(pm.text, pm.links)
            if (newHash != ex.contentHash) {
                val oldLinks = LinkCodec.decode(ex.currentLinks)
                val cls = EditClassifier.classify(ex.currentText, oldLinks, pm.text, pm.links)
                val version = ex.versionCount + 1
                val encoded = LinkCodec.encode(pm.links)
                updated = ex.copy(
                    currentText = pm.text, currentLinks = encoded, contentHash = newHash,
                    versionCount = version, lastCheckedAt = now,
                )
                versions += MessageVersionEntity(
                    messageRowId = ex.id, content = pm.text, links = encoded,
                    contentHash = newHash, detectedAt = now, versionNumber = version,
                )
                events += ChangeEventEntity(
                    channelId = ch.id, messageId = pm.id, changeType = cls.type,
                    detail = cls.details.joinToString(","),
                    oldContent = ContentFormat.display(ex.currentText, oldLinks),
                    newContent = ContentFormat.display(pm.text, pm.links),
                    detectedAt = now, verificationStatus = VerificationStatus.CONFIRMED,
                )
                notes += ScanNotification(NotificationKind.EDIT, ch.id, channelTitle, pm.id, now, cls.details.joinToString(","))
                edits++
            }
            if (updated.deletionStatus != DeletionStatus.ACTIVE || updated.missingScans != 0) {
                updated = updated.copy(
                    deletionStatus = DeletionStatus.ACTIVE, missingScans = 0, deletionDetectedAt = null, lastCheckedAt = now,
                )
            }
            if (updated != ex) updates += updated
        }

        // Deletion detection only runs on a healthy page that actually listed posts.
        if (all.isNotEmpty()) {
            val present = all.mapTo(HashSet()) { it.id }
            var budget = maxVerifications
            for (m in existing) {
                if (m.messageId in present || m.deletionStatus == DeletionStatus.CONFIRMED_DELETED) continue
                val verdict = if (budget > 0) { budget--; verifyDeletion(ch.username, m.messageId) } else Verify.ERROR
                val missing = m.missingScans + 1
                when (verdict) {
                    Verify.EXISTS -> {
                        if (m.deletionStatus != DeletionStatus.ACTIVE || m.missingScans != 0) {
                            updates += m.copy(deletionStatus = DeletionStatus.ACTIVE, missingScans = 0, deletionDetectedAt = null)
                        }
                    }
                    Verify.GONE -> {
                        updates += m.copy(
                            deletionStatus = DeletionStatus.CONFIRMED_DELETED, missingScans = missing,
                            deletionDetectedAt = m.deletionDetectedAt ?: now, lastCheckedAt = now,
                        )
                        events += deletionEvent(ch.id, m, ChangeType.DELETION_CONFIRMED, VerificationStatus.CONFIRMED, now)
                        notes += ScanNotification(NotificationKind.DELETION_CONFIRMED, ch.id, channelTitle, m.messageId, now, null)
                        confirmed++
                    }
                    Verify.INCONCLUSIVE, Verify.ERROR -> {
                        val status = if (missing >= SUSPECT_AFTER_SCANS) DeletionStatus.SUSPECTED_DELETED else DeletionStatus.UNKNOWN
                        updates += m.copy(deletionStatus = status, missingScans = missing, deletionDetectedAt = m.deletionDetectedAt ?: now)
                        if (status == DeletionStatus.SUSPECTED_DELETED && m.deletionStatus != DeletionStatus.SUSPECTED_DELETED) {
                            events += deletionEvent(ch.id, m, ChangeType.DELETION_SUSPECTED, VerificationStatus.UNCONFIRMED, now)
                            notes += ScanNotification(NotificationKind.DELETION_SUSPECTED, ch.id, channelTitle, m.messageId, now, null)
                            suspected++
                        }
                    }
                }
            }
        }

        // Batches: each new-post chunk advances the checkpoint in the same transaction that stores it.
        val chunks = newItems.sortedBy { it.message.messageId }.chunked(batchSize)
        var committedMax = ch.lastKnownMessageId
        for (i in 0 until chunks.size - 1) {
            val chunk = chunks[i]
            committedMax = maxOf(committedMax, chunk.last().message.messageId)
            store.commit(
                ScanCommit(
                    channelId = ch.id,
                    state = ChannelScanState(
                        lastKnownMessageId = committedMax, status = ch.status,
                        consecutiveFailures = ch.consecutiveFailures, lastError = ch.lastError,
                    ),
                    newMessages = chunk,
                )
            )
        }
        store.commit(
            ScanCommit(
                channelId = ch.id,
                state = ChannelScanState(
                    title = title, lastKnownMessageId = maxOf(ch.lastKnownMessageId, maxId),
                    lastSuccessfulCheck = now, status = ChannelStatus.OK, consecutiveFailures = 0,
                    lastError = null, baselineSet = true,
                ),
                newMessages = chunks.lastOrNull() ?: emptyList(),
                updates = updates, newVersions = versions, events = events,
                touchedMessageIds = all.filter { existingById.containsKey(it.id) }.map { it.id },
                touchTime = now,
            )
        )

        val newCount = newItems.count { it.event != null }
        acc.newPosts += newCount
        acc.edits += edits
        acc.suspected += suspected
        acc.confirmed += confirmed
        acc.notifications += notes
        return PassResult.Done(needsRecheck = newCount > 0 || pages > 1)
    }

    private fun newMessage(channelId: Long, pm: ParsedMessage, now: Long) = MessageEntity(
        channelId = channelId, messageId = pm.id,
        originalText = pm.text, currentText = pm.text,
        originalLinks = LinkCodec.encode(pm.links), currentLinks = LinkCodec.encode(pm.links),
        contentHash = ContentHasher.hash(pm.text, pm.links),
        publishedAt = pm.publishedAt, firstSeenAt = now, lastCheckedAt = now, url = pm.url,
    )

    private fun deletionEvent(channelId: Long, m: MessageEntity, type: String, verification: String, now: Long) =
        ChangeEventEntity(
            channelId = channelId, messageId = m.messageId, changeType = type,
            oldContent = ContentFormat.display(m.currentText, LinkCodec.decode(m.currentLinks)),
            detectedAt = now, verificationStatus = verification,
        )

    private suspend fun verifyDeletion(username: String, messageId: Long): Verify =
        when (val r = fetcher.fetch("https://t.me/$username/$messageId?embed=1&mode=tme")) {
            is FetchResult.Ok -> when (TelegramPageParser.classifyEmbed(r.html)) {
                EmbedState.EXISTS -> Verify.EXISTS
                EmbedState.GONE -> Verify.GONE
                EmbedState.INCONCLUSIVE -> Verify.INCONCLUSIVE
            }
            else -> Verify.ERROR
        }

    private suspend fun fetchPage(ch: ChannelEntity, before: Long?, pagination: Boolean): PageOutcome {
        val url = if (before == null) "https://t.me/s/${ch.username}" else "https://t.me/s/${ch.username}?before=$before"
        return when (val r = fetcher.fetch(url)) {
            is FetchResult.NetworkError ->
                PageOutcome.Failed(Failure(ChannelStatus.NETWORK_ERROR, "خطأ شبكة: ${r.message}"))
            is FetchResult.HttpError -> PageOutcome.Failed(
                when {
                    r.code == 404 -> Failure(ChannelStatus.NOT_FOUND, "HTTP 404")
                    r.code == 403 -> Failure(ChannelStatus.ACCESS_DENIED, "HTTP 403")
                    r.code == 429 -> Failure(ChannelStatus.RATE_LIMITED, "HTTP 429 (تقييد من الخادم)", rateLimited = true)
                    r.code >= 500 -> Failure(ChannelStatus.SERVER_ERROR, "HTTP ${r.code}")
                    else -> Failure(ChannelStatus.NETWORK_ERROR, "HTTP ${r.code}")
                }
            )
            is FetchResult.Ok -> when (val p = TelegramPageParser.parseChannelPage(r.html, r.finalUrl, ch.username)) {
                is PageParse.Messages -> PageOutcome.Ok(p.title, p.messages)
                is PageParse.Empty ->
                    // A previously active channel suddenly "empty" is a parse problem, not an empty channel.
                    if (pagination || !(ch.baselineSet && ch.lastKnownMessageId > 0)) PageOutcome.Ok(p.title, emptyList())
                    else PageOutcome.Failed(Failure(ChannelStatus.PARSE_FAILURE, "صفحة فارغة غير متوقعة لقناة فيها منشورات"))
                PageParse.PreviewDisabled -> PageOutcome.Failed(
                    Failure(ChannelStatus.PREVIEW_DISABLED, "المعاينة العامة موقفة؛ القراءة تتطلب تسجيل دخول")
                )
                PageParse.NotAChannel -> PageOutcome.Failed(
                    Failure(ChannelStatus.NOT_FOUND, "القناة غير موجودة أو ليست قناة عامة")
                )
                is PageParse.Broken -> PageOutcome.Failed(Failure(ChannelStatus.PARSE_FAILURE, p.reason))
            }
        }
    }

    companion object {
        const val MAX_PASSES = 3
        const val SUSPECT_AFTER_SCANS = 2
        const val FAILURE_NOTIFY_THRESHOLD = 3
    }
}
