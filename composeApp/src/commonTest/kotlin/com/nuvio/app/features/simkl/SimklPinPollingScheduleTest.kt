package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimklPinPollingScheduleTest {
    @Test
    fun missingPersistedScheduleUsesLegacyV1Defaults() {
        val schedule = buildSimklPinPollingSchedule(
            intervalSeconds = null,
            expiresInSeconds = null,
            startedAtEpochMs = 1_000L,
        )

        assertEquals(5, schedule.intervalSeconds)
        assertEquals(901_000L, schedule.expiresAtEpochMs)
    }

    @Test
    fun serverScheduleIsPreservedAndExpiryIsAbsoluteAcrossReloads() {
        val schedule = buildSimklPinPollingSchedule(
            intervalSeconds = 8,
            expiresInSeconds = 900,
            startedAtEpochMs = 10_000L,
        )

        assertEquals(8, schedule.intervalSeconds)
        assertEquals(910_000L, schedule.expiresAtEpochMs)
        assertTrue(isSimklPinAuthorizationExpired(schedule.expiresAtEpochMs, 910_000L))
    }

    @Test
    fun expiryNeverPrecedesOnePollingInterval() {
        val schedule = buildSimklPinPollingSchedule(
            intervalSeconds = 10,
            expiresInSeconds = 1,
            startedAtEpochMs = 0L,
        )

        assertEquals(10, schedule.intervalSeconds)
        assertEquals(10_000L, schedule.expiresAtEpochMs)
    }
}
