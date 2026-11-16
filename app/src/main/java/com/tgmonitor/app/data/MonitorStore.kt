package com.tgmonitor.app.data

/** A newly seen message plus the event (null for the silent baseline scan). */
class NewItem(val message: MessageEntity, val event: ChangeEventEntity?)

/** Partial channel update. Null fields stay unchanged; status/failures/error are always written. */
class ChannelScanState(
    val title: String? = null,
    val lastKnownMessageId: Long? = null,
    val lastSuccessfulCheck: Long? = null,
    val status: String,
    val consecutiveFailures: Int,
    val lastError: String?,
    val baselineSet: Boolean? = null,
)

/** Everything one batch writes, applied atomically (checkpoint included). */
class ScanCommit(
    val channelId: Long,
    val state: ChannelScanState,
    val newMessages: List<NewItem> = emptyList(),
    val updates: List<MessageEntity> = emptyList(),
    val newVersions: List<MessageVersionEntity> = emptyList(),
    val events: List<ChangeEventEntity> = emptyList(),
    val touchedMessageIds: List<Long> = emptyList(),
    val touchTime: Long = 0L,
)

interface MonitorStore {
    suspend fun getChannel(id: Long): ChannelEntity?
    suspend fun findChannelByUsername(username: String): ChannelEntity?

    /** Returns the new id, or null when the username already exists. */
    suspend fun insertChannel(channel: ChannelEntity): Long?
    suspend fun deleteChannel(id: Long)
    suspend fun setMonitoring(id: Long, enabled: Boolean)
    suspend fun getEnabledChannelsStalestFirst(): List<ChannelEntity>
    suspend fun updateScanState(channelId: Long, state: ChannelScanState)

    suspend fun getMessagesFrom(channelId: Long, minMessageId: Long): List<MessageEntity>
    suspend fun commit(commit: ScanCommit)

    suspend fun startScan(channelId: Long, startedAt: Long): Long
    suspend fun finishScan(scanId: Long, completedAt: Long, status: String, postsScanned: Int, error: String?)
}
