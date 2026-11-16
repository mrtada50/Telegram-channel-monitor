package com.tgmonitor.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction

class RoomMonitorStore(private val db: AppDatabase) : MonitorStore {
    override suspend fun getChannel(id: Long) = db.channelDao().getById(id)

    override suspend fun findChannelByUsername(username: String) = db.channelDao().getByUsername(username)

    override suspend fun insertChannel(channel: ChannelEntity): Long? =
        try {
            db.channelDao().insert(channel)
        } catch (e: SQLiteConstraintException) {
            null
        }

    override suspend fun deleteChannel(id: Long) = db.channelDao().deleteById(id)

    override suspend fun setMonitoring(id: Long, enabled: Boolean) = db.channelDao().setMonitoring(id, enabled)

    override suspend fun getEnabledChannelsStalestFirst() = db.channelDao().getEnabledStalestFirst()

    override suspend fun updateScanState(channelId: Long, state: ChannelScanState) {
        db.channelDao().updateScanState(
            channelId, state.title, state.lastKnownMessageId, state.lastSuccessfulCheck,
            state.status, state.consecutiveFailures, state.lastError, state.baselineSet,
        )
    }

    override suspend fun getMessagesFrom(channelId: Long, minMessageId: Long) =
        db.messageDao().getFromId(channelId, minMessageId)

    override suspend fun commit(commit: ScanCommit) {
        db.withTransaction {
            val messages = db.messageDao()
            val versions = db.versionDao()
            val changes = db.changeDao()
            for (item in commit.newMessages) {
                val rowId = messages.insert(item.message)
                if (rowId == -1L) continue // already stored: never duplicate
                versions.insert(
                    MessageVersionEntity(
                        messageRowId = rowId,
                        content = item.message.originalText,
                        links = item.message.originalLinks,
                        contentHash = item.message.contentHash,
                        detectedAt = item.message.firstSeenAt,
                        versionNumber = 1,
                    )
                )
                item.event?.let { changes.insert(it) }
            }
            commit.updates.forEach { messages.update(it) }
            commit.newVersions.forEach { versions.insert(it) }
            commit.events.forEach { changes.insert(it) }
            commit.touchedMessageIds.chunked(500).forEach { messages.touch(commit.channelId, it, commit.touchTime) }
            val s = commit.state
            db.channelDao().updateScanState(
                commit.channelId, s.title, s.lastKnownMessageId, s.lastSuccessfulCheck,
                s.status, s.consecutiveFailures, s.lastError, s.baselineSet,
            )
        }
    }

    override suspend fun startScan(channelId: Long, startedAt: Long): Long =
        db.scanDao().insert(ScanHistoryEntity(channelId = channelId, startedAt = startedAt, status = "RUNNING"))

    override suspend fun finishScan(scanId: Long, completedAt: Long, status: String, postsScanned: Int, error: String?) =
        db.scanDao().finish(scanId, completedAt, status, postsScanned, error)
}
