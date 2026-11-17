package com.tgmonitor.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lightweight row for lists: only a 160-char preview, never the full text. */
data class MessageListItem(
    val id: Long,
    val channelId: Long,
    val messageId: Long,
    val preview: String,
    val publishedAt: Long?,
    val firstSeenAt: Long,
    val deletionStatus: String,
    val versionCount: Int,
)

data class ChangeListItem(
    val id: Long,
    val channelId: Long,
    val messageId: Long,
    val changeType: String,
    val detail: String?,
    val preview: String?,
    val detectedAt: Long,
)

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(channel: ChannelEntity): Long

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    fun observeById(id: Long): Flow<ChannelEntity?>

    @Query("SELECT * FROM channels WHERE username = :username COLLATE NOCASE LIMIT 1")
    suspend fun getByUsername(username: String): ChannelEntity?

    @Query("SELECT * FROM channels ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY id")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE monitoringEnabled = 1 ORDER BY COALESCE(lastSuccessfulCheck, 0) ASC")
    suspend fun getEnabledStalestFirst(): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels WHERE status = 'PREVIEW_DISABLED'")
    fun observePreviewDisabledCount(): Flow<Int>

    @Query("UPDATE channels SET monitoringEnabled = :enabled WHERE id = :id")
    suspend fun setMonitoring(id: Long, enabled: Boolean)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE channels SET title = COALESCE(:title, title), " +
            "lastKnownMessageId = COALESCE(:lastKnown, lastKnownMessageId), " +
            "lastSuccessfulCheck = COALESCE(:lastSuccess, lastSuccessfulCheck), " +
            "status = :status, consecutiveFailures = :failures, lastError = :error, " +
            "baselineSet = COALESCE(:baselineSet, baselineSet) WHERE id = :id"
    )
    suspend fun updateScanState(
        id: Long,
        title: String?,
        lastKnown: Long?,
        lastSuccess: Long?,
        status: String,
        failures: Int,
        error: String?,
        baselineSet: Boolean?,
    )
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND messageId >= :minId ORDER BY messageId")
    suspend fun getFromId(channelId: Long, minId: Long): List<MessageEntity>

    @Query("UPDATE messages SET lastCheckedAt = :time WHERE channelId = :channelId AND messageId IN (:ids)")
    suspend fun touch(channelId: Long, ids: List<Long>, time: Long)

    @Query(
        "SELECT id, channelId, messageId, substr(currentText, 1, 160) AS preview, publishedAt, firstSeenAt, " +
            "deletionStatus, versionCount FROM messages WHERE channelId = :channelId ORDER BY messageId DESC LIMIT 200"
    )
    fun observeForChannel(channelId: Long): Flow<List<MessageListItem>>

    @Query("SELECT * FROM messages WHERE channelId = :channelId AND messageId = :messageId LIMIT 1")
    fun observeOne(channelId: Long, messageId: Long): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE channelId = :channelId")
    fun observeCountForChannel(channelId: Long): Flow<Int>

    @Query("SELECT * FROM messages ORDER BY id")
    suspend fun getAll(): List<MessageEntity>
}

@Dao
interface VersionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(version: MessageVersionEntity): Long

    @Query("SELECT * FROM message_versions WHERE messageRowId = :rowId ORDER BY versionNumber")
    fun observeForMessage(rowId: Long): Flow<List<MessageVersionEntity>>

    @Query("SELECT COUNT(*) FROM message_versions")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM message_versions ORDER BY id")
    suspend fun getAll(): List<MessageVersionEntity>
}

@Dao
interface ChangeDao {
    @Insert
    suspend fun insert(event: ChangeEventEntity): Long

    @Query(
        "SELECT id, channelId, messageId, changeType, detail, " +
            "substr(COALESCE(newContent, oldContent, ''), 1, 160) AS preview, detectedAt " +
            "FROM change_events WHERE (:channelId IS NULL OR channelId = :channelId) " +
            "AND (:filterTypes = 0 OR changeType IN (:types)) AND detectedAt >= :since " +
            "ORDER BY detectedAt DESC, id DESC LIMIT 300"
    )
    fun observeFiltered(channelId: Long?, filterTypes: Boolean, types: List<String>, since: Long): Flow<List<ChangeListItem>>

    @Query(
        "SELECT id, channelId, messageId, changeType, detail, " +
            "substr(COALESCE(newContent, oldContent, ''), 1, 160) AS preview, detectedAt " +
            "FROM change_events WHERE channelId = :channelId ORDER BY detectedAt DESC, id DESC LIMIT 200"
    )
    fun observeForChannel(channelId: Long): Flow<List<ChangeListItem>>

    @Query(
        "SELECT id, channelId, messageId, changeType, detail, " +
            "substr(COALESCE(newContent, oldContent, ''), 1, 160) AS preview, detectedAt " +
            "FROM change_events ORDER BY detectedAt DESC, id DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<ChangeListItem>>

    @Query("SELECT COUNT(*) FROM change_events WHERE changeType IN (:types) AND detectedAt >= :since")
    fun observeCountSince(types: List<String>, since: Long): Flow<Int>

    @Query("SELECT * FROM change_events ORDER BY id")
    suspend fun getAll(): List<ChangeEventEntity>
}

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(scan: ScanHistoryEntity): Long

    @Query("UPDATE scan_history SET completedAt = :completedAt, status = :status, postsScanned = :posts, errorMessage = :error WHERE id = :id")
    suspend fun finish(id: Long, completedAt: Long, status: String, posts: Int, error: String?)

    @Query("SELECT MAX(completedAt) FROM scan_history WHERE status = 'SUCCESS'")
    fun observeLastSuccess(): Flow<Long?>

    @Query("SELECT * FROM scan_history ORDER BY id")
    suspend fun getAll(): List<ScanHistoryEntity>
}
