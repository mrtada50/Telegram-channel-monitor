package com.tgmonitor.app.account

import com.tgmonitor.app.engine.MessageSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

sealed interface AccountState {
    data object NotAvailable : AccountState
    data object NotConfigured : AccountState
    data object Starting : AccountState
    data object WaitPhone : AccountState
    data object WaitCode : AccountState
    data object WaitPassword : AccountState
    data object Ready : AccountState
    class Failed(val message: String) : AccountState
}

sealed interface JoinResult {
    data object Joined : JoinResult
    class Wait(val minutes: Int) : JoinResult
    class Failed(val message: String) : JoinResult
}

interface AccountSession {
    /** false when this build was made without the TDLib library. */
    val available: Boolean
    val state: StateFlow<AccountState>
    val lastError: StateFlow<String?>
    val source: MessageSource

    suspend fun configure(apiId: Int, apiHash: String)
    suspend fun awaitReady(timeoutMs: Long): Boolean
    fun submitPhone(phone: String)
    fun submitCode(code: String)
    fun submitPassword(password: String)
    suspend fun join(username: String): JoinResult
    fun logout()
}

/**
 * Makes account access look like a person opening channels one at a time:
 * a random pause (12–40 s) when moving to a different channel, and a short random pause between
 * requests inside the same channel. Never parallel.
 */
class Pacer(
    private val minChannelGapMs: Long = 12_000L,
    private val maxChannelGapMs: Long = 40_000L,
    private val minPageGapMs: Long = 700L,
    private val maxPageGapMs: Long = 2_200L,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: (Long, Long) -> Long = { a, b -> Random.nextLong(a, b + 1) },
) {
    private val mutex = Mutex()
    private var lastKey: String? = null
    private var lastAt = 0L

    suspend fun before(channelKey: String) {
        mutex.withLock {
            val gap = when {
                lastKey == null -> 0L
                lastKey != channelKey -> random(minChannelGapMs, maxChannelGapMs)
                else -> random(minPageGapMs, maxPageGapMs)
            }
            val wait = lastAt + gap - now()
            if (wait > 0) sleep(wait)
            lastKey = channelKey
            lastAt = now()
        }
    }
}

/** Joining channels is always user-initiated and spaced out: at most one join per 10 minutes. */
object JoinPolicy {
    const val MIN_GAP_MS = 10L * 60_000L
    fun waitMs(lastJoinAt: Long, now: Long): Long = maxOf(0L, lastJoinAt + MIN_GAP_MS - now)
}
