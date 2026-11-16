package com.tgmonitor.app.testutil

import com.tgmonitor.app.data.ChangeEventEntity
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelScanState
import com.tgmonitor.app.data.MessageEntity
import com.tgmonitor.app.data.MessageVersionEntity
import com.tgmonitor.app.data.MonitorStore
import com.tgmonitor.app.data.ScanCommit
import com.tgmonitor.app.data.ScanHistoryEntity

/** In-memory MonitorStore mirroring RoomMonitorStore semantics (unique keys, atomic commit). */
class FakeStore : MonitorStore {
    val channels = mutableListOf<ChannelEntity>()
    val messages = mutableListOf<MessageEntity>()
    val versions = mutableListOf<MessageVersionEntity>()
    val events = mutableListOf<ChangeEventEntity>()
    val scans = mutableListOf<ScanHistoryEntity>()
    var commitCount = 0
    private var nextId = 1L

    override suspend fun getChannel(id: Long) = channels.firstOrNull { it.id == id }

    override suspend fun findChannelByUsername(username: String) =
        channels.firstOrNull { it.username.equals(username, ignoreCase = true) }

    override suspend fun insertChannel(channel: ChannelEntity): Long? {
        if (findChannelByUsername(channel.username) != null) return null
        val id = nextId++
        channels += channel.copy(id = id)
        return id
    }

    override suspend fun deleteChannel(id: Long) {
        channels.removeAll { it.id == id }
        val rowIds = messages.filter { it.channelId == id }.map { it.id }.toSet()
        messages.removeAll { it.channelId == id }
        versions.removeAll { it.messageRowId in rowIds }
        events.removeAll { it.channelId == id }
    }

    override suspend fun setMonitoring(id: Long, enabled: Boolean) {
        val i = channels.indexOfFirst { it.id == id }
        if (i >= 0) channels[i] = channels[i].copy(monitoringEnabled = enabled)
    }

    override suspend fun getEnabledChannelsStalestFirst() =
        channels.filter { it.monitoringEnabled }.sortedBy { it.lastSuccessfulCheck ?: 0L }

    override suspend fun updateScanState(channelId: Long, state: ChannelScanState) {
        val i = channels.indexOfFirst { it.id == channelId }
        if (i < 0) return
        val c = channels[i]
        channels[i] = c.copy(
            title = state.title ?: c.title,
            lastKnownMessageId = state.lastKnownMessageId ?: c.lastKnownMessageId,
            lastSuccessfulCheck = state.lastSuccessfulCheck ?: c.lastSuccessfulCheck,
            status = state.status,
            consecutiveFailures = state.consecutiveFailures,
            lastError = state.lastError,
            baselineSet = state.baselineSet ?: c.baselineSet,
        )
    }

    override suspend fun getMessagesFrom(channelId: Long, minMessageId: Long) =
        messages.filter { it.channelId == channelId && it.messageId >= minMessageId }.sortedBy { it.messageId }

    override suspend fun commit(commit: ScanCommit) {
        commitCount++
        for (item in commit.newMessages) {
            if (messages.any { it.channelId == item.message.channelId && it.messageId == item.message.messageId }) continue
            val rowId = nextId++
            messages += item.message.copy(id = rowId)
            versions += MessageVersionEntity(
                id = nextId++, messageRowId = rowId, content = item.message.originalText,
                links = item.message.originalLinks, contentHash = item.message.contentHash,
                detectedAt = item.message.firstSeenAt, versionNumber = 1,
            )
            item.event?.let { events += it.copy(id = nextId++) }
        }
        commit.updates.forEach { u ->
            val i = messages.indexOfFirst { it.id == u.id }
            if (i >= 0) messages[i] = u
        }
        versions += commit.newVersions.map { it.copy(id = nextId++) }
        events += commit.events.map { it.copy(id = nextId++) }
        val touched = commit.touchedMessageIds.toSet()
        for (i in messages.indices) {
            if (messages[i].channelId == commit.channelId && messages[i].messageId in touched) {
                messages[i] = messages[i].copy(lastCheckedAt = commit.touchTime)
            }
        }
        updateScanState(commit.channelId, commit.state)
    }

    override suspend fun startScan(channelId: Long, startedAt: Long): Long {
        val id = nextId++
        scans += ScanHistoryEntity(id = id, channelId = channelId, startedAt = startedAt, status = "RUNNING")
        return id
    }

    override suspend fun finishScan(scanId: Long, completedAt: Long, status: String, postsScanned: Int, error: String?) {
        val i = scans.indexOfFirst { it.id == scanId }
        if (i >= 0) scans[i] = scans[i].copy(completedAt = completedAt, status = status, postsScanned = postsScanned, errorMessage = error)
    }
}
