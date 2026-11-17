package com.tgmonitor.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgmonitor.app.AppContainer
import com.tgmonitor.app.data.AppSettings
import com.tgmonitor.app.data.ChangeListItem
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ExportFormat
import com.tgmonitor.app.data.MessageEntity
import com.tgmonitor.app.data.MessageListItem
import com.tgmonitor.app.data.MessageVersionEntity
import com.tgmonitor.app.engine.AddChannelResult
import com.tgmonitor.app.engine.NotificationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

data class HomeState(
    val channels: Int = 0,
    val messages: Int = 0,
    val editsToday: Int = 0,
    val deletionsToday: Int = 0,
    val lastScan: Long? = null,
    val settings: AppSettings = AppSettings(),
    val previewDisabled: Int = 0,
    val recent: List<ChangeListItem> = emptyList(),
    val names: Map<Long, String> = emptyMap(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val since = TimeUtil.startOfToday()
    private val counts = combine(
        c.db.channelDao().observeCount(),
        c.db.messageDao().observeCount(),
        c.db.changeDao().observeCountSince(ChangeType.EDITS, since),
        c.db.changeDao().observeCountSince(ChangeType.DELETIONS, since),
        c.db.scanDao().observeLastSuccess(),
    ) { ch, ms, ed, de, last -> HomeState(ch, ms, ed, de, last) }

    val state: StateFlow<HomeState> = combine(
        counts,
        c.settings.settings,
        c.db.channelDao().observePreviewDisabledCount(),
        c.db.changeDao().observeRecent(5),
        c.db.channelDao().observeAll(),
    ) { s, set, pd, recent, chans ->
        s.copy(settings = set, previewDisabled = pd, recent = recent, names = chans.associate { it.id to it.title })
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, HomeState())

    val scanning: StateFlow<Boolean> = c.scheduler.manualScanRunning().stateIn(viewModelScope, WHILE_SUBSCRIBED, false)

    fun scanNow() = c.scheduler.scanNow(null, manual = true)
}

class ChannelsViewModel(private val c: AppContainer) : ViewModel() {
    val channels: StateFlow<List<ChannelEntity>> =
        c.db.channelDao().observeAll().stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val adding = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun add(input: String) {
        if (adding.value) return
        adding.value = true
        // appScope: the initial scan must finish even if the screen is left.
        c.appScope.launch {
            val result = c.channelManager.add(input)
            message.value = when (result) {
                AddChannelResult.InvalidInput -> "الإدخال غير صالح. استخدم @username أو رابط t.me لقناة عامة."
                AddChannelResult.Duplicate -> "هذه القناة مضافة مسبقاً."
                AddChannelResult.NotFound -> "القناة غير موجودة أو ليست قناة عامة."
                is AddChannelResult.Added -> when {
                    result.previewDisabled ->
                        "تمت إضافة القناة، لكن المعاينة العامة لها موقفة، فلا يمكن مراقبتها بدون تسجيل دخول."
                    result.initialScanFailed ->
                        "تمت إضافة القناة، وتعذر الفحص الأولي الآن وسيُعاد تلقائياً."
                    else -> "تمت إضافة القناة. المنشورات الحالية سُجلت كنقطة بداية ولن تُعدّ جديدة."
                }
            }
            adding.value = false
        }
    }

    fun dismissMessage() { message.value = null }
    fun setMonitoring(id: Long, enabled: Boolean) { c.appScope.launch { c.channelManager.setMonitoring(id, enabled) } }
    fun remove(id: Long) { c.appScope.launch { c.channelManager.remove(id) } }
    fun scan(id: Long) = c.scheduler.scanNow(id, manual = true)
}

class ChannelDetailsViewModel(c: AppContainer, private val channelId: Long) : ViewModel() {
    val channel: StateFlow<ChannelEntity?> = c.db.channelDao().observeById(channelId).stateIn(viewModelScope, WHILE_SUBSCRIBED, null)
    val messages: StateFlow<List<MessageListItem>> =
        c.db.messageDao().observeForChannel(channelId).stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val events: StateFlow<List<ChangeListItem>> =
        c.db.changeDao().observeForChannel(channelId).stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    private val scheduler = c.scheduler
    fun scan() = scheduler.scanNow(channelId, manual = true)
}

enum class DateRange(val label: String) {
    TODAY("اليوم"), WEEK("7 أيام"), MONTH("30 يوماً"), ALL("الكل");

    fun since(): Long = when (this) {
        TODAY -> TimeUtil.startOfToday()
        WEEK -> System.currentTimeMillis() - 7L * 86_400_000L
        MONTH -> System.currentTimeMillis() - 30L * 86_400_000L
        ALL -> 0L
    }
}

enum class TypeFilter(val label: String, val types: List<String>) {
    ALL("كل الأنواع", emptyList()),
    NEW("منشورات جديدة", listOf(ChangeType.NEW_POST)),
    EDIT("تعديلات", ChangeType.EDITS),
    DELETE("حذف", ChangeType.DELETIONS),
}

data class ChangeFilter(val channelId: Long? = null, val type: TypeFilter = TypeFilter.ALL, val range: DateRange = DateRange.ALL)

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModel(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(ChangeFilter())
    val channels: StateFlow<List<ChannelEntity>> =
        c.db.channelDao().observeAll().stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
    val events: StateFlow<List<ChangeListItem>> = filter.flatMapLatest { f ->
        c.db.changeDao().observeFiltered(
            f.channelId, f.type != TypeFilter.ALL,
            if (f.type.types.isEmpty()) listOf("") else f.type.types, f.range.since(),
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessageHistoryViewModel(c: AppContainer, channelId: Long, messageId: Long) : ViewModel() {
    val message: StateFlow<MessageEntity?> =
        c.db.messageDao().observeOne(channelId, messageId).stateIn(viewModelScope, WHILE_SUBSCRIBED, null)
    val channel: StateFlow<ChannelEntity?> =
        c.db.channelDao().observeById(channelId).stateIn(viewModelScope, WHILE_SUBSCRIBED, null)
    val versions: StateFlow<List<MessageVersionEntity>> = message.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else c.db.versionDao().observeForMessage(m.id)
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, emptyList())
}

data class StorageStats(val channels: Int = 0, val messages: Int = 0, val versions: Int = 0, val dbBytes: Long = 0)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, WHILE_SUBSCRIBED, AppSettings())
    val stats: StateFlow<StorageStats> = combine(
        c.db.channelDao().observeCount(), c.db.messageDao().observeCount(), c.db.versionDao().observeCount(),
    ) { ch, ms, v -> StorageStats(ch, ms, v, c.dbFileSize()) }
        .flowOn(Dispatchers.IO) // file stat off the main thread
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, StorageStats())
    val exportMessage = MutableStateFlow<String?>(null)

    fun setInterval(minutes: Int) {
        c.appScope.launch {
            c.settings.setInterval(minutes)
            if (c.settings.current().monitoringEnabled) c.scheduler.schedule(minutes, replace = true)
        }
    }

    fun setMonitoring(enabled: Boolean) {
        c.appScope.launch {
            c.settings.setMonitoringEnabled(enabled)
            if (enabled) c.scheduler.schedule(c.settings.current().intervalMinutes, replace = true) else c.scheduler.cancelPeriodic()
        }
    }

    fun setNotification(kind: NotificationKind, enabled: Boolean) {
        c.appScope.launch { c.settings.setNotification(kind, enabled) }
    }

    fun export(uri: Uri, format: ExportFormat) {
        c.appScope.launch {
            exportMessage.value = try {
                c.exporter.exportZip(uri, format)
                "تم التصدير بنجاح (${format.ext.uppercase()})."
            } catch (e: Exception) {
                "فشل التصدير: ${e.message}"
            }
        }
    }
}
