package com.nuvio.app.features.player

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExoSeekStallWatchdogTest {
    private val timeoutMs = 15_000L

    @Test
    fun reportsOnlyAfterFullActiveBufferingWindow() {
        val watchdog = ExoSeekStallWatchdog()
        watchdog.beginAttempt(1)
        assertTrue(watchdog.recordSeek(1, 100L, 10_000L, 90_000L))

        assertNull(watchdog.poll(1, 1_000L, Player.STATE_BUFFERING, true, false, 90_000L, timeoutMs))
        assertNull(watchdog.poll(1, 15_999L, Player.STATE_BUFFERING, true, false, 90_000L, timeoutMs))
        val report = watchdog.poll(1, 16_000L, Player.STATE_BUFFERING, true, false, 90_000L, timeoutMs)

        assertEquals(10_000L, report?.fromPositionMs)
        assertEquals(90_000L, report?.targetPositionMs)
        assertEquals(timeoutMs, report?.activeBufferingMs)
        assertEquals(15_900L, report?.seekElapsedMs)
        assertNull(watchdog.poll(1, 40_000L, Player.STATE_BUFFERING, true, false, 90_000L, timeoutMs))
    }

    @Test
    fun pauseAndSuppressionDoNotConsumeTheTimeout() {
        val watchdog = ExoSeekStallWatchdog()
        watchdog.beginAttempt(2)
        watchdog.recordSeek(2, 0L, 5_000L, 30_000L)

        assertNull(watchdog.poll(2, 1_000L, Player.STATE_BUFFERING, true, false, 30_000L, timeoutMs))
        assertNull(watchdog.poll(2, 20_000L, Player.STATE_BUFFERING, false, false, 30_000L, timeoutMs))
        assertNull(watchdog.poll(2, 40_000L, Player.STATE_BUFFERING, true, true, 30_000L, timeoutMs))
        assertNull(watchdog.poll(2, 69_998L, Player.STATE_BUFFERING, true, false, 30_000L, timeoutMs))
        assertTrue(watchdog.poll(2, 69_999L, Player.STATE_BUFFERING, true, false, 30_000L, timeoutMs) != null)
    }

    @Test
    fun readyIdleAndEndedClearPendingSeek() {
        for (state in listOf(Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED)) {
            val watchdog = ExoSeekStallWatchdog()
            watchdog.beginAttempt(3)
            watchdog.recordSeek(3, 0L, 0L, 50_000L)

            assertNull(watchdog.poll(3, 1_000L, state, true, false, 50_000L, timeoutMs))
            assertNull(watchdog.poll(3, 40_000L, Player.STATE_BUFFERING, true, false, 50_000L, timeoutMs))
        }
    }

    @Test
    fun aNewSeekReplacesThePreviousTargetAndResetsTheWindow() {
        val watchdog = ExoSeekStallWatchdog()
        watchdog.beginAttempt(4)
        watchdog.recordSeek(4, 0L, 0L, 20_000L)
        assertNull(watchdog.poll(4, 10_000L, Player.STATE_BUFFERING, true, false, 20_000L, timeoutMs))

        watchdog.recordSeek(4, 10_001L, 20_000L, 80_000L)
        assertNull(watchdog.poll(4, 39_999L, Player.STATE_BUFFERING, true, false, 80_000L, timeoutMs))
        val report = watchdog.poll(4, 40_000L, Player.STATE_BUFFERING, true, false, 80_000L, timeoutMs)
        assertEquals(20_000L, report?.fromPositionMs)
        assertEquals(80_000L, report?.targetPositionMs)
    }

    @Test
    fun staleAttemptsAndInvalidSeekPositionsAreRejected() {
        val watchdog = ExoSeekStallWatchdog()
        watchdog.beginAttempt(5)
        assertFalse(watchdog.recordSeek(4, 0L, 0L, 1_000L))
        assertFalse(watchdog.recordSeek(5, -1L, 0L, 1_000L))
        assertFalse(watchdog.recordSeek(5, 0L, -1L, 1_000L))
        assertFalse(watchdog.recordSeek(5, 0L, 0L, -1L))

        watchdog.beginAttempt(6)
        assertNull(watchdog.poll(5, 100_000L, Player.STATE_BUFFERING, true, false, 1_000L, timeoutMs))
    }
}
