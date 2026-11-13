package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvRequestGenerationTest {

    @Test
    fun newerRefreshSupersedesAnOlderRefresh() {
        val generation = LiveTvRequestGeneration()

        val first = generation.begin()
        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun profileResetInvalidatesAnInFlightRefresh() {
        val generation = LiveTvRequestGeneration()
        val inFlight = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(inFlight))
    }
}
