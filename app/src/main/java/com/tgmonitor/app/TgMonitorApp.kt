package com.tgmonitor.app

import android.app.Application
import android.content.Context
import com.tgmonitor.app.account.AccountSession
import com.tgmonitor.app.account.AccountSessionFactory
import com.tgmonitor.app.account.AccountStore
import com.tgmonitor.app.account.SecretStore
import com.tgmonitor.app.data.AppDatabase
import com.tgmonitor.app.data.DataExporter
import com.tgmonitor.app.data.MonitorStore
import com.tgmonitor.app.data.RoomMonitorStore
import com.tgmonitor.app.data.SettingsRepository
import com.tgmonitor.app.engine.ChannelManager
import com.tgmonitor.app.engine.ScanEngine
import com.tgmonitor.app.scraper.OkHttpPageFetcher
import com.tgmonitor.app.proxy.ProxyAwareFetcher
import com.tgmonitor.app.proxy.ProxyManager
import com.tgmonitor.app.scraper.PageFetcher
import com.tgmonitor.app.work.Notifier
import com.tgmonitor.app.work.ScanScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.build(appContext) }
    val store: MonitorStore by lazy { RoomMonitorStore(db) }
    val settings = SettingsRepository(appContext)
    val proxy: ProxyManager by lazy { ProxyManager(appContext, appScope) }
    // Direct first; if Telegram looks blocked, retry through a proxy that is verified to serve Telegram pages.
    val fetcher: PageFetcher by lazy { ProxyAwareFetcher(OkHttpPageFetcher(), proxy) }
    val accountStore: AccountStore by lazy { AccountStore(appContext, SecretStore()) }
    val account: AccountSession by lazy { AccountSessionFactory.create(appContext, accountStore, appScope) }
    val engine: ScanEngine by lazy {
        ScanEngine(
            store, fetcher, pageDelayMs = 2_000L, confirmDelayMs = 3_000L,
            accountSource = account.source,
            // Account is used only for channels the user switched to it, and only while logged in.
            useAccount = { ch -> account.available && accountStore.channelUsesAccount(ch.id) && account.awaitReady(20_000L) },
        )
    }
    val channelManager: ChannelManager by lazy { ChannelManager(store, engine) }
    val scheduler = ScanScheduler(appContext)
    val notifier = Notifier(appContext)
    val exporter: DataExporter by lazy { DataExporter(appContext, db) }

    fun dbFileSize(): Long = appContext.getDatabasePath("tgmonitor.db").let { if (it.exists()) it.length() else 0L }
}

class TgMonitorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()
        container.appScope.launch {
            container.scheduler.ensureScheduled(container.settings.current())
        }
    }
}
