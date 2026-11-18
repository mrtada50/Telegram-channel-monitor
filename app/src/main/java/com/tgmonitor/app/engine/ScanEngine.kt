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
import com.tgmonitor.app.scraper.PageFetcher
import com.tgmonitor.app.scraper.ParsedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Scans one channel: finds every new post since the last committed checkpoint (with pagination),
 * detects edits, and verifies deletions. The checkpoint only advances inside the same transaction
 * that stores the posts, so an unsaved post is never skipped.
 *
 * False-positive protection: an edit or a deletion is only believed after a SECOND independent
 * fetch agrees with the first (stale CDN pages, truncated pages and parsing noise do not repeat).
 */
class ScanEngine(
    private val store: MonitorStore,
    fetcher: PageFetcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxPages: Int = 100,
    private val batchSize: Int = 100,
    private val maxVerifications: Int = 10,
    private val pageDelayMs: Long = 0L,
    private val confirmDelayMs: Long = 0L,
    /** Logged-in Telegram account source (optional). Used only for channels where [useAccount] says so. */
    private val accountSource: MessageSource? = null,
    private val useAccount: suspend (ChannelEntity) -> Boolean = { false },
) {
    private val htmlSource: MessageSource = HtmlMessageSource(fetcher)

    private suspend fun sourceFor(ch: ChannelEntity): MessageSource {
        val account = accountSource
        return if (account != null && useAccount(ch)) account else htmlSource
    }

    private class Accumulator {
        var posts = 0
        var newPosts = 0
        var edits = 0
        var suspected = 0
        var confirmed = 0
        val notifications = mutableListOf<ScanNotification>()
    }

    private class Failure(val channelStatus: String?, val message: String, val rateLimited: Boolean = false)

    private class Collected(val title: String?, val all: List<ParsedMessage>, val pages: Int)

    private sealed interface CollectOutcome {
        class Ok(val collected: Collected) : CollectOutcome
        class Failed(val failure: Failure) : CollectOutcome
    }

    private class Candidates(val existing: List<MessageEntity>, val edits: Map<Long, String>, val missing: Set<Long>)

    private sealed interface PassResult {
        class Done(val needsRecheck: Boolean) : PassResult
        class Failed(val failure: Failure) : PassResult
    }

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

    /** Fetches the newest page and walks backwards until it reaches already-checkpointed territory. */
    private suspend fun collect(ch: ChannelEntity, src: MessageSource): CollectOutcome {
        val canPaginate = ch.baselineSet && ch.lastKnownMessageId > 0
        val first = when (val p = src.page(ch, null, false)) {
            is SourcePage.Failed -> return CollectOutcome.Failed(p.toFailure())
            is SourcePage.Ok -> p
        }
        val collected = HashMap<Long, ParsedMessage>()
        first.messages.forEach { collected[it.id] = it }
        var pages = 1
        var cursor: Long? = collected.keys.minOrNull()
        while (canPaginate && cursor != null && cursor > ch.lastKnownMessageId && pages < maxPages) {
            val before: Long = cursor
            if (pageDelayMs > 0) delay(jitter(pageDelayMs))
            val p = when (val r = src.page(ch, before, true)) {
                is SourcePage.Failed -> return CollectOutcome.Failed(r.toFailure()) // abort: a gap must not be committed
                is SourcePage.Ok -> r
            }
            val older = p.messages.filter { it.id < before }
            if (older.isEmpty()) break
            older.forEach { collected[it.id] = it }
            pages++
            cursor = older.minOf { it.id }
        }
        return CollectOutcome.Ok(
            Collected(first.title?.takeIf { it.isNotBlank() }, collected.values.sortedBy { it.id }, pages)
        )
    }

    private suspend fun findCandidates(ch: ChannelEntity, all: List<ParsedMessage>): Candidates {
        if (all.isEmpty()) return Candidates(emptyList(), emptyMap(), emptySet())
        val existing = store.getMessagesFrom(ch.id, all.first().id)
        val byId = existing.associateBy { it.messageId }
        val edits = HashMap<Long, String>()
        for (pm in all) {
            val ex = byId[pm.id] ?: continue
            val h = ContentHasher.hash(pm.text, pm.links)
            if (h != ex.contentHash) edits[pm.id] = h
        }
        val present = all.mapTo(HashSet()) { it.id }
        val newest = all.last().id
        // Only posts OLDER than the newest visible post can be judged missing: a stale or partial
        // page lacks the newest posts, never the older ones.
        val missing = existing
            .filter { it.messageId !in present && it.messageId < newest && it.deletionStatus != DeletionStatus.CONFIRMED_DELETED }
            .map { it.messageId }
            .toSet()
        return Candidates(existing, edits, missing)
    }

    private suspend fun runPass(channelId: Long, acc: Accumulator): PassResult {
        val ch = store.getChannel(channelId) ?: return PassResult.Failed(Failure(null, "القناة حُذفت أثناء الفحص"))
        val isBaseline = !ch.baselineSet
        val src = sourceFor(ch)

        var data = when (val r = collect(ch, src)) {
            is CollectOutcome.Failed -> return PassResult.Failed(r.failure)
            is CollectOutcome.Ok -> r.collected
        }
        var cand = findCandidates(ch, data.all)
        var confirmedEdits: Set<Long> = cand.edits.keys
        var confirmedMissing: Set<Long> = cand.missing

        if (!isBaseline && (cand.edits.isNotEmpty() || cand.missing.isNotEmpty())) {
            // Second, independent look before believing an edit or a deletion.
            if (confirmDelayMs > 0) delay(jitter(confirmDelayMs))
            when (val r2 = collect(ch, src)) {
                is CollectOutcome.Failed -> {
                    confirmedEdits = emptySet()
                    confirmedMissing = emptySet()
                }
                is CollectOutcome.Ok -> {
                    val cand2 = findCandidates(ch, r2.collected.all)
                    confirmedEdits = cand.edits.filter { (id, hash) -> cand2.edits[id] == hash }.keys
                    confirmedMissing = cand.missing.intersect(cand2.missing)
                    data = r2.collected
                    cand = cand2
                }
            }
        }

        val all = data.all
        acc.posts += all.size
        val now = clock()
        val title = data.title
        val channelTitle = title ?: ch.title
        val maxId = all.lastOrNull()?.id ?: 0L
        val existing = cand.existing
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
                // A post whose id is at or below the checkpoint cannot be new: it is an older post the
                // baseline window did not cover. Store it silently (no event, no notification).
                val silent = isBaseline || pm.id <= ch.lastKnownMessageId
                val event = if (silent) null else ChangeEventEntity(
                    channelId = ch.id, messageId = pm.id, changeType = ChangeType.NEW_POST,
                    newContent = ContentFormat.display(pm.text, pm.links), detectedAt = now,
                    verificationStatus = VerificationStatus.NOT_APPLICABLE,
                )
                newItems += NewItem(newMessage(ch.id, pm, now), event)
                if (!silent) notes += ScanNotification(NotificationKind.NEW_POST, ch.id, channelTitle, pm.id, now, null)
                continue
            }
            var updated = ex
            val newHash = ContentHasher.hash(pm.text, pm.links)
            if (newHash != ex.contentHash && pm.id in confirmedEdits) {
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

        // Deletion detection: only posts missing in BOTH independent fetches are examined.
        if (all.isNotEmpty() && confirmedMissing.isNotEmpty()) {
            var budget = maxVerifications
            for (m in existing) {
                if (m.messageId !in confirmedMissing) continue
                val verdict = if (budget > 0) { budget--; src.check(ch, m.messageId) } else DeletionCheck.ERROR
                val missing = m.missingScans + 1
                when (verdict) {
                    DeletionCheck.EXISTS -> {
                        if (m.deletionStatus != DeletionStatus.ACTIVE || m.missingScans != 0) {
                            updates += m.copy(deletionStatus = DeletionStatus.ACTIVE, missingScans = 0, deletionDetectedAt = null)
                        }
                    }
                    DeletionCheck.GONE -> {
                        updates += m.copy(
                            deletionStatus = DeletionStatus.CONFIRMED_DELETED, missingScans = missing,
                            deletionDetectedAt = m.deletionDetectedAt ?: now, lastCheckedAt = now,
                        )
                        events += deletionEvent(ch.id, m, ChangeType.DELETION_CONFIRMED, VerificationStatus.CONFIRMED, now)
                        notes += ScanNotification(NotificationKind.DELETION_CONFIRMED, ch.id, channelTitle, m.messageId, now, null)
                        confirmed++
                    }
                    DeletionCheck.INCONCLUSIVE, DeletionCheck.ERROR -> {
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
        return PassResult.Done(needsRecheck = newCount > 0 || data.pages > 1)
    }

    private fun jitter(baseMs: Long): Long = (baseMs * (0.6 + Random.nextDouble() * 0.9)).toLong()

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

    private fun SourcePage.Failed.toFailure() = Failure(channelStatus, message, rateLimited)

    companion object {
        const val MAX_PASSES = 3
        const val SUSPECT_AFTER_SCANS = 3
        const val FAILURE_NOTIFY_THRESHOLD = 3
    }
}
