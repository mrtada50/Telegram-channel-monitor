package com.tgmonitor.app.engine

enum class NotificationKind(val label: String) {
    NEW_POST("منشور جديد"),
    EDIT("تعديل منشور"),
    DELETION_CONFIRMED("حذف مؤكد"),
    DELETION_SUSPECTED("حذف مشتبه به"),
    SCAN_FAILED("فشل فحص القناة"),
    CHANNEL_UNREACHABLE("تعذر الوصول إلى القناة"),
    PREVIEW_DISABLED("المعاينة العامة موقفة"),
}

data class ScanNotification(
    val kind: NotificationKind,
    val channelId: Long,
    val channelTitle: String,
    val messageId: Long?,
    val detectedAt: Long,
    val detail: String?,
)

data class ScanOutcome(
    val status: String,
    val newPosts: Int = 0,
    val edits: Int = 0,
    val suspectedDeletions: Int = 0,
    val confirmedDeletions: Int = 0,
    val postsScanned: Int = 0,
    val notifications: List<ScanNotification> = emptyList(),
    val rateLimited: Boolean = false,
    val error: String? = null,
    val channelStatus: String? = null,
) {
    val success: Boolean get() = status == "SUCCESS"
}
