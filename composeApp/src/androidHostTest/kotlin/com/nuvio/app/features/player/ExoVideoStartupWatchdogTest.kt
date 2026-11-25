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
    fun frameGapRequiresAFirstFrameAndKnownVideoFrameRate() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(10)

        assertFalse(watchdog.shouldReportGap(10, 0L, 0L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        watchdog.recordFrame(10, 1_000L, 1_000L, 1_000_000L, 0f)
        assertFalse(watchdog.shouldReportGap(10, 8_000L, 8_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))

        watchdog.beginAttempt(11)
        watchdog.recordFrame(11, 1_000L, 1_000L, 1_000_000L, 24f)
        assertTrue(watchdog.shouldReportGap(11, 6_000L, 3_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
    }

    @Test
    fun frameGapReportsOnlyWhenPlaybackPositionContinuesAdvancing() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(12)
        watchdog.recordFrame(12, 1_000L, 1_000L, 1_000_000L, 24f)

        assertFalse(watchdog.shouldReportGap(12, 7_000L, 1_500L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertTrue(watchdog.shouldReportGap(12, 7_000L, 3_100L, true, Player.STATE_READY, true, 5_000L, 2_000L))
    }

    @Test
    fun frameGapDoesNotCountPausedBufferingOrAudioOnlyTime() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(13)
        watchdog.recordFrame(13, 1_000L, 1_000L, 1_000_000L, 24f)

        assertFalse(watchdog.shouldReportGap(13, 3_000L, 3_000L, false, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(13, 8_000L, 3_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(13, 10_000L, 3_000L, true, Player.STATE_BUFFERING, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(13, 16_000L, 3_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(13, 17_000L, 3_000L, true, Player.STATE_READY, false, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(13, 23_000L, 3_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertTrue(watchdog.shouldReportGap(13, 28_000L, 5_100L, true, Player.STATE_READY, true, 5_000L, 2_000L))
    }

    @Test
    fun aNewFrameRestartsTheGapWindowAndAllowsAnotherReport() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(14)
        watchdog.recordFrame(14, 1_000L, 1_000L, 1_000_000L, 24f)

        assertTrue(watchdog.shouldReportGap(14, 6_000L, 3_500L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(14, 9_000L, 6_500L, true, Player.STATE_READY, true, 5_000L, 2_000L))

        watchdog.recordFrame(14, 9_500L, 7_000L, 7_000_000L, 24f)
        assertFalse(watchdog.shouldReportGap(14, 14_000L, 11_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertTrue(watchdog.shouldReportGap(14, 15_000L, 13_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
    }

    @Test
    fun backwardSeekRestartsTheFrameGapWindow() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(17)
        watchdog.recordFrame(17, 1_000L, 30_000L, 30_000_000L, 24f)

        assertFalse(watchdog.shouldReportGap(17, 7_000L, 1_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(17, 11_000L, 2_500L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertTrue(watchdog.shouldReportGap(17, 12_000L, 3_100L, true, Player.STATE_READY, true, 5_000L, 2_000L))
    }

    @Test
    fun staleFrameCallbacksCannotUpdateANewerAttempt() {
        val watchdog = VideoFrameOutputGapWatchdog()
        watchdog.beginAttempt(15)
        watchdog.recordFrame(15, 1_000L, 1_000L, 1_000_000L, 24f)
        watchdog.beginAttempt(16)
        watchdog.recordFrame(15, 50_000L, 50_000L, 50_000_000L, 24f)

        assertFalse(watchdog.shouldReportGap(15, 60_000L, 60_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
        assertFalse(watchdog.shouldReportGap(16, 60_000L, 60_000L, true, Player.STATE_READY, true, 5_000L, 2_000L))
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
