package com.tgmonitor.app.account

import android.content.Context
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.engine.DeletionCheck
import com.tgmonitor.app.engine.MessageSource
import com.tgmonitor.app.engine.SourcePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Used when the app is built without the TDLib library (default). */
object AccountSessionFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, store: AccountStore, scope: CoroutineScope): AccountSession = NoTdlibSession()
}

private class NoTdlibSession : AccountSession {
    override val available: Boolean = false
    override val state: StateFlow<AccountState> = MutableStateFlow(AccountState.NotAvailable)
    override val lastError: StateFlow<String?> = MutableStateFlow(null)
    override val source: MessageSource = object : MessageSource {
        override suspend fun page(ch: ChannelEntity, before: Long?, pagination: Boolean): SourcePage =
            SourcePage.Failed(ChannelStatus.ACCESS_DENIED, "هذه النسخة بُنيت بدون TDLib")

        override suspend fun check(ch: ChannelEntity, messageId: Long): DeletionCheck = DeletionCheck.ERROR
    }

    override suspend fun configure(apiId: Int, apiHash: String) = Unit
    override suspend fun awaitReady(timeoutMs: Long): Boolean = false
    override fun submitPhone(phone: String) = Unit
    override fun submitCode(code: String) = Unit
    override fun submitPassword(password: String) = Unit
    override suspend fun join(username: String): JoinResult = JoinResult.Failed("غير متاح في هذه النسخة")
    override fun logout() = Unit
}
