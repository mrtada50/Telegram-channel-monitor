package com.tgmonitor.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ChannelStatus {
    const val PENDING = "PENDING"
    const val OK = "OK"
    const val PREVIEW_DISABLED = "PREVIEW_DISABLED"
    const val NOT_FOUND = "NOT_FOUND"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val SERVER_ERROR = "SERVER_ERROR"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val PARSE_FAILURE = "PARSE_FAILURE"
}

object DeletionStatus {
    const val ACTIVE = "ACTIVE"
    const val SUSPECTED_DELETED = "SUSPECTED_DELETED"
    const val CONFIRMED_DELETED = "CONFIRMED_DELETED"
    const val UNKNOWN = "UNKNOWN"
}

object ChangeType {
    const val NEW_POST = "NEW_POST"
    const val TEXT_EDITED = "TEXT_EDITED"
    const val LINKS_EDITED = "LINKS_EDITED"
    const val TEXT_AND_LINKS_EDITED = "TEXT_AND_LINKS_EDITED"
    const val DELETION_SUSPECTED = "DELETION_SUSPECTED"
    const val DELETION_CONFIRMED = "DELETION_CONFIRMED"
    val EDITS = listOf(TEXT_EDITED, LINKS_EDITED, TEXT_AND_LINKS_EDITED)
    val DELETIONS = listOf(DELETION_SUSPECTED, DELETION_CONFIRMED)
}

object VerificationStatus {
    const val NOT_APPLICABLE = "NOT_APPLICABLE"
    const val CONFIRMED = "CONFIRMED"
    const val UNCONFIRMED = "UNCONFIRMED"
}

@Entity(tableName = "channels", indices = [Index(value = ["username"], unique = true)])
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val title: String,
    val url: String,
    val dateAdded: Long,
    val lastSuccessfulCheck: Long? = null,
    val monitoringEnabled: Boolean = true,
    val lastKnownMessageId: Long = 0,
    val status: String = ChannelStatus.PENDING,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    /** true after the first successful scan; that scan is the silent baseline. */
    val baselineSet: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["channelId", "messageId"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val messageId: Long,
    val originalText: String,
    val currentText: String,
    /** links separated by newline */
    val originalLinks: String = "",
    val currentLinks: String = "",
    val contentHash: String,
    val publishedAt: Long? = null,
    val firstSeenAt: Long,
    val lastCheckedAt: Long,
    val deletionStatus: String = DeletionStatus.ACTIVE,
    val missingScans: Int = 0,
    val deletionDetectedAt: Long? = null,
    val versionCount: Int = 1,
    val url: String = "",
)

@Entity(
    tableName = "message_versions",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["messageRowId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["messageRowId", "versionNumber"], unique = true)],
)
data class MessageVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageRowId: Long,
    val content: String,
    val links: String = "",
    val contentHash: String,
    val detectedAt: Long,
    val versionNumber: Int,
)

@Entity(
    tableName = "change_events",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["channelId", "detectedAt"]), Index(value = ["detectedAt"])],
)
data class ChangeEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    /** Telegram message id (not the row id). */
    val messageId: Long,
    val changeType: String,
    val detail: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
    val detectedAt: Long,
    val verificationStatus: String = VerificationStatus.NOT_APPLICABLE,
)

@Entity(
    tableName = "scan_history",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["channelId", "startedAt"])],
)
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val postsScanned: Int = 0,
    val errorMessage: String? = null,
)
