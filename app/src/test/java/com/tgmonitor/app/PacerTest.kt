package com.tgmonitor.app

import com.tgmonitor.app.account.JoinPolicy
import com.tgmonitor.app.account.Pacer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTest {
    private class Clock {
        var t = 1_000_000L
        val sleeps = mutableListOf<Long>()
    }

    private fun pacer(c: Clock) = Pacer(
        minChannelGapMs = 12_000L, maxChannelGapMs = 40_000L, minPageGapMs = 700L, maxPageGapMs = 2_200L,
        now = { c.t }, sleep = { ms -> c.sleeps += ms; c.t += ms }, random = { a, _ -> a },
    )

    @Test fun firstRequestIsImmediate() = runTest {
        val c = Clock(); pacer(c).before("a")
        assertTrue(c.sleeps.isEmpty())
    }

    @Test fun switchingChannelWaitsLikeAPersonAndNeverRunsTwoAtOnce() = runTest {
        val c = Clock(); val p = pacer(c)
        p.before("a")
        p.before("b")
        assertEquals(listOf(12_000L), c.sleeps)       // long pause between different channels
        p.before("b")
        assertEquals(listOf(12_000L, 700L), c.sleeps) // short pause inside the same channel
    }

    @Test fun elapsedTimeCountsTowardsThePause() = runTest {
        val c = Clock(); val p = pacer(c)
        p.before("a")
        c.t += 5_000L
        p.before("b")
        assertEquals(listOf(7_000L), c.sleeps)
    }

    @Test fun joinsAreSpacedByTenMinutes() {
        assertEquals(0L, JoinPolicy.waitMs(0L, 1_000_000_000L))
        assertEquals(JoinPolicy.MIN_GAP_MS, JoinPolicy.waitMs(5_000L, 5_000L))
        assertEquals(60_000L, JoinPolicy.waitMs(0L, JoinPolicy.MIN_GAP_MS - 60_000L))
    }
}
