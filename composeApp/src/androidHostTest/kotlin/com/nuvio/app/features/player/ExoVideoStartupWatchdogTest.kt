package com.nuvio.app.features.player

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExoVideoStartupWatchdogTest {

    private val timeoutMs = 20_000L

    @Test
    fun doesNotStartTimeoutUntilVideoIsSelectedAndActuallyPlaying() {
        val watchdog = ExoVideoStartupWatchdog()
        watchdog.beginAttempt(1)

        assertFalse(watchdog.shouldReportNoFirstFrame(1, 0L, true, Player.STATE_READY, false, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(1, 60_000L, false, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(1, 60_000L, true, Player.STATE_BUFFERING, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(1, 60_000L, true, Player.STATE_READY, true, timeoutMs))
        assertTrue(watchdog.shouldReportNoFirstFrame(1, 80_000L, true, Player.STATE_READY, true, timeoutMs))
    }

    @Test
    fun timeoutIsOneShotForAnAttempt() {
        val watchdog = ExoVideoStartupWatchdog()
        watchdog.beginAttempt(7)

        assertFalse(watchdog.shouldReportNoFirstFrame(7, 1_000L, true, Player.STATE_READY, true, timeoutMs))
        assertTrue(watchdog.shouldReportNoFirstFrame(7, 21_000L, true, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(7, 50_000L, true, Player.STATE_READY, true, timeoutMs))
    }

    @Test
    fun firstFrameDisarmsTheNoFrameWatchdog() {
        val watchdog = ExoVideoStartupWatchdog()
        watchdog.beginAttempt(2)

        assertFalse(watchdog.shouldReportNoFirstFrame(2, 1_000L, true, Player.STATE_READY, true, timeoutMs))
        watchdog.markFirstFrame(2)

        assertFalse(watchdog.shouldReportNoFirstFrame(2, 60_000L, true, Player.STATE_READY, true, timeoutMs))
    }

    @Test
    fun staleAttemptCannotReportOrDisarmCurrentAttempt() {
        val watchdog = ExoVideoStartupWatchdog()
        watchdog.beginAttempt(3)
        watchdog.beginAttempt(4)

        watchdog.markFirstFrame(3)

        assertFalse(watchdog.shouldReportNoFirstFrame(3, 100_000L, true, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(4, 1_000L, true, Player.STATE_READY, true, timeoutMs))
        assertTrue(watchdog.shouldReportNoFirstFrame(4, 21_000L, true, Player.STATE_READY, true, timeoutMs))
    }

    @Test
    fun firstFrameIdentityMustMatchTheCurrentPrepareAttempt() {
        val diagnostics = PlaybackDiagnostics()
        diagnostics.beginAttempt(1_000L, expectedMediaIdentity = "id:current")

        assertTrue(diagnostics.isCurrentMediaIdentity("id:current"))
        assertFalse(diagnostics.isCurrentMediaIdentity("id:previous"))
        assertFalse(diagnostics.isCurrentMediaIdentity(null))
    }

    @Test
    fun pauseBufferingAndTrackLossResetTheActiveWindow() {
        val watchdog = ExoVideoStartupWatchdog()
        watchdog.beginAttempt(5)

        assertFalse(watchdog.shouldReportNoFirstFrame(5, 1_000L, true, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 15_000L, false, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 30_000L, true, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 40_000L, true, Player.STATE_BUFFERING, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 50_000L, true, Player.STATE_READY, true, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 55_000L, true, Player.STATE_READY, false, timeoutMs))
        assertFalse(watchdog.shouldReportNoFirstFrame(5, 60_000L, true, Player.STATE_READY, true, timeoutMs))
        assertTrue(watchdog.shouldReportNoFirstFrame(5, 80_000L, true, Player.STATE_READY, true, timeoutMs))
    }
}
