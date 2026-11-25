package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvPlaybackProgressWatchdogTest {
    private fun healthyWarmup(watchdog: MpvPlaybackProgressWatchdog, attempt: Int = 1) {
        watchdog.beginAttempt(attempt)
        var now = 0L
        var position = 0L
        watchdog.sample(attempt, now, position, 1f, true)
        repeat(5) {
            now += 3_000L
            position += 3_000L
            assertNull(watchdog.sample(attempt, now, position, 1f, true))
        }
    }

    @Test
    fun requiresHealthyWarmupBeforeReportingSustainedCrawl() {
        val watchdog = MpvPlaybackProgressWatchdog()
        watchdog.beginAttempt(1)
        watchdog.sample(1, 0L, 0L, 1f, true)
        repeat(4) { index ->
            assertNull(watchdog.sample(1, (index + 1) * 3_000L, 1L, 1f, true))
        }
        // A session that has not established healthy playback never reports a stall.
        repeat(8) { index ->
            assertNull(watchdog.sample(1, (index + 5) * 3_000L, 2L, 1f, true))
        }
    }

    @Test
    fun reportsOnceAfterEstablishedPlaybackCrawls() {
        val watchdog = MpvPlaybackProgressWatchdog()
        healthyWarmup(watchdog)
        var now = 15_000L
        var position = 15_000L
        repeat(4) {
            now += 3_000L
            assertNull(watchdog.sample(1, now, position, 1f, true))
        }
        now += 3_000L
        val report = watchdog.sample(1, now, position, 1f, true)
        assertEquals(1, report?.attempt)
        assertEquals(5, report?.consecutiveSlowSamples)
        assertNull(watchdog.sample(1, now + 3_000L, position, 1f, true))
    }

    @Test
    fun scalesExpectedProgressByPlaybackSpeed() {
        val watchdog = MpvPlaybackProgressWatchdog()
        healthyWarmup(watchdog)
        var now = 18_000L
        var position = 18_000L
        assertNull(watchdog.sample(1, now, position, 2f, true))
        // At 2x speed, 300 ms of movement per 3-second interval is below
        // the 15% threshold (900 ms), even though it would pass at 1x.
        repeat(5) {
            now += 3_000L
            position += 300L
            val report = watchdog.sample(1, now, position, 2f, true)
            if (it < 4) assertNull(report) else assertEquals(5, report?.consecutiveSlowSamples)
        }
    }

    @Test
    fun ineligiblePeriodRebaselinesWithoutCountingPauseAsStall() {
        val watchdog = MpvPlaybackProgressWatchdog()
        healthyWarmup(watchdog)
        assertNull(watchdog.sample(1, 18_000L, 18_000L, 1f, false))
        assertNull(watchdog.sample(1, 60_000L, 18_000L, 1f, true))
        assertNull(watchdog.sample(1, 63_000L, 21_000L, 1f, true))
    }

    @Test
    fun newAttemptRejectsStaleSamplesAndResetsWarmup() {
        val watchdog = MpvPlaybackProgressWatchdog()
        healthyWarmup(watchdog)
        watchdog.beginAttempt(2)
        assertNull(watchdog.sample(1, 18_000L, 18_000L, 1f, true))
        assertNull(watchdog.sample(2, 0L, 0L, 1f, true))
        assertTrue(watchdog.sample(2, 3_000L, 3_000L, 1f, true) == null)
    }
}
