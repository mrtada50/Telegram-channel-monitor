package com.tgmonitor.app.engine

import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.data.MonitorStore

sealed interface AddChannelResult {
    data object InvalidInput : AddChannelResult
    data object Duplicate : AddChannelResult
    data object NotFound : AddChannelResult
    class Added(val channel: ChannelEntity, val previewDisabled: Boolean, val initialScanFailed: Boolean) : AddChannelResult
}

class ChannelManager(
    private val store: MonitorStore,
    private val engine: ScanEngine,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun add(input: String): AddChannelResult {
        val username = ChannelInputParser.parse(input) ?: return AddChannelResult.InvalidInput
        if (store.findChannelByUsername(username) != null) return AddChannelResult.Duplicate
        val id = store.insertChannel(
            ChannelEntity(username = username, title = username, url = "https://t.me/$username", dateAdded = clock())
        ) ?: return AddChannelResult.Duplicate

        // Initial scan = silent baseline: current posts are stored but never reported as new.
        val outcome = engine.scanChannel(id)
        if (outcome.channelStatus == ChannelStatus.NOT_FOUND) {
            store.deleteChannel(id)
            return AddChannelResult.NotFound
        }
        val channel = store.getChannel(id) ?: return AddChannelResult.NotFound
        return AddChannelResult.Added(
            channel = channel,
            previewDisabled = channel.status == ChannelStatus.PREVIEW_DISABLED,
            initialScanFailed = !outcome.success && channel.status != ChannelStatus.PREVIEW_DISABLED,
        )
    }

    suspend fun remove(id: Long) = store.deleteChannel(id)

    suspend fun setMonitoring(id: Long, enabled: Boolean) = store.setMonitoring(id, enabled)
}
