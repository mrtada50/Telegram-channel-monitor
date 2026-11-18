package com.tgmonitor.app.account

import android.content.Context
import android.os.Build
import com.tgmonitor.app.engine.MessageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume

/** Compiled only when the app is built with -PenableTdlib=true (see app/build.gradle.kts). */
object AccountSessionFactory {
    fun create(context: Context, store: AccountStore, scope: CoroutineScope): AccountSession =
        TdlibSession(context.applicationContext, store, scope)
}

internal class TdlibSession(
    private val context: Context,
    private val store: AccountStore,
    private val scope: CoroutineScope,
) : AccountSession {

    override val available: Boolean = true
    private val _state = MutableStateFlow<AccountState>(AccountState.NotConfigured)
    override val state: StateFlow<AccountState> = _state
    private val _error = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _error
    override val source: MessageSource = TdlibMessageSource(this, Pacer())

    @Volatile private var client: Client? = null
    @Volatile private var restartAfterClose = false
    private var apiId = 0
    private var apiHash = ""
    private var dbKey = ByteArray(0)
    private val startMutex = Mutex()

    private val errorHandler = Client.ResultHandler { res ->
        if (res is TdApi.Error) _error.value = "${res.code}: ${res.message}" else _error.value = null
    }

    private suspend fun start() {
        startMutex.withLock {
            if (client != null) return
            val creds = store.credentials()
            if (creds == null) {
                _state.value = AccountState.NotConfigured
                return
            }
            apiId = creds.first
            apiHash = creds.second
            dbKey = store.databaseKey()
            _state.value = AccountState.Starting
            Client.execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = 1 })
            client = Client.create(Client.ResultHandler { obj -> onUpdate(obj) }, null, null)
        }
    }

    override suspend fun configure(apiId: Int, apiHash: String) {
        store.saveCredentials(apiId, apiHash)
        _error.value = null
        val c = client
        if (c != null) {
            restartAfterClose = true
            c.send(TdApi.Close(), errorHandler)
        } else {
            start()
        }
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        start()
        val s = withTimeoutOrNull(timeoutMs) { state.first { it !is AccountState.Starting } }
        return s is AccountState.Ready
    }

    private fun onUpdate(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) onAuth(obj.authorizationState)
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _state.value = AccountState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _state.value = AccountState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> _state.value = AccountState.WaitPassword
            is TdApi.AuthorizationStateReady -> {
                _state.value = AccountState.Ready
                _error.value = null
            }
            is TdApi.AuthorizationStateClosed -> {
                client = null
                if (restartAfterClose) {
                    restartAfterClose = false
                    scope.launch { start() }
                } else {
                    _state.value = AccountState.Failed("أُغلق الاتصال")
                }
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> Unit
            else -> _state.value = AccountState.Failed("طريقة تسجيل الدخول هذه غير مدعومة في التطبيق")
        }
    }

    private fun sendParameters() {
        val p = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.filesDir, "tdlib").absolutePath
            filesDirectory = File(context.cacheDir, "tdfiles").absolutePath
            databaseEncryptionKey = dbKey
            useFileDatabase = false
            useChatInfoDatabase = true
            useMessageDatabase = false // always fetch fresh messages, keep storage small
            useSecretChats = false
            apiId = this@TdlibSession.apiId
            apiHash = this@TdlibSession.apiHash
            systemLanguageCode = "ar"
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = "1.1.0"
        }
        client?.send(p, errorHandler)
    }

    private fun fire(f: TdApi.Function<*>) {
        client?.send(f, errorHandler) ?: run { _error.value = "العميل غير مشغّل" }
    }

    override fun submitPhone(phone: String) = fire(TdApi.SetAuthenticationPhoneNumber().apply { phoneNumber = phone.trim() })
    override fun submitCode(code: String) = fire(TdApi.CheckAuthenticationCode().apply { this.code = code.trim() })
    override fun submitPassword(password: String) = fire(TdApi.CheckAuthenticationPassword().apply { this.password = password })

    override fun logout() {
        restartAfterClose = true
        fire(TdApi.LogOut())
    }

    suspend fun call(f: TdApi.Function<*>): TdApi.Object = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resume(TdApi.Error(500, "العميل غير مشغّل"))
        } else {
            c.send(f, Client.ResultHandler { obj -> if (cont.isActive) cont.resume(obj) })
        }
    }

    override suspend fun join(username: String): JoinResult {
        if (state.value !is AccountState.Ready) return JoinResult.Failed("سجّل الدخول أولاً")
        val wait = JoinPolicy.waitMs(store.lastJoinAt(), System.currentTimeMillis())
        if (wait > 0) return JoinResult.Wait(((wait + 59_999L) / 60_000L).toInt())
        val chat = call(TdApi.SearchPublicChat().apply { this.username = username })
        if (chat !is TdApi.Chat) return JoinResult.Failed((chat as? TdApi.Error)?.message ?: "تعذر العثور على القناة")
        val res = call(TdApi.JoinChat().apply { chatId = chat.id })
        if (res is TdApi.Error) return JoinResult.Failed(res.message)
        store.setLastJoinAt(System.currentTimeMillis())
        return JoinResult.Joined
    }
}
