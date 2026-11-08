package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionGenerationTest {
    @Test
    fun newerSessionGenerationInvalidatesEarlierCollector() {
        val generation = AuthSessionGeneration()

        val first = generation.begin()
        assertTrue(generation.isCurrent(first))

        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun invalidationPreventsAnOldCollectorFromPublishing() {
        val generation = AuthSessionGeneration()
        val attempt = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(attempt))
    }
}
