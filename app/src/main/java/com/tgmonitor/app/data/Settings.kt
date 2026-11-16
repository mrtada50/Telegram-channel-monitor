package com.tgmonitor.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tgmonitor.app.engine.IntervalPolicy
import com.tgmonitor.app.engine.NotificationKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val intervalMinutes: Int = IntervalPolicy.DEFAULT,
    val monitoringEnabled: Boolean = true,
    val notifications: Map<NotificationKind, Boolean> = emptyMap(),
) {
    fun isEnabled(kind: NotificationKind): Boolean = notifications[kind] ?: true
}

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val intervalKey = intPreferencesKey("interval_minutes")
    private val monitoringKey = booleanPreferencesKey("monitoring_enabled")
    private fun notifyKey(kind: NotificationKind) = booleanPreferencesKey("notify_${kind.name}")

    val settings: Flow<AppSettings> = appContext.settingsStore.data.map { p: Preferences ->
        AppSettings(
            intervalMinutes = p[intervalKey] ?: IntervalPolicy.DEFAULT,
            monitoringEnabled = p[monitoringKey] ?: true,
            notifications = NotificationKind.values().associateWith { p[notifyKey(it)] ?: true },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setInterval(minutes: Int) {
        appContext.settingsStore.edit { it[intervalKey] = minutes }
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        appContext.settingsStore.edit { it[monitoringKey] = enabled }
    }

    suspend fun setNotification(kind: NotificationKind, enabled: Boolean) {
        appContext.settingsStore.edit { it[notifyKey(kind)] = enabled }
    }
}
