package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginProfileOperationGateTest {
    @Test
    fun overlappingAccountCleanupHoldsRemainClosedUntilAllFinish() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        gate.beginAccountCleanup()
        gate.beginAccountCleanup()

        gate.endAccountCleanup()
        assertTrue(gate.current(1) == null)

        gate.endAccountCleanup()
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }


    @Test
    fun accountCleanupBlocksNewPluginWorkUntilCleanupFinishes() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        val old = assertNotNull(gate.current(1))
        gate.beginAccountCleanup()

        var cached = false
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.runIfCurrent(old) { cached = true })
        assertFalse(cached)
        assertTrue(gate.current(1) == null)

        gate.endAccountCleanup()
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }


    @Test
    fun profileChangeInvalidatesPreviousPluginRefresh() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        val old = assertNotNull(gate.current(1))

        gate.invalidate(2)

        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(assertNotNull(gate.current(2))))
    }

    @Test
    fun accountCleanupInvalidatesPendingPluginCacheWrites() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        val old = assertNotNull(gate.current(1))
        var cached = false

        gate.invalidate(1)

        assertFalse(gate.runIfCurrent(old) { cached = true })
        assertFalse(cached)
    }

    @Test
    fun returningToEarlierProfileDoesNotReviveOldRefresh() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        val first = assertNotNull(gate.current(1))

        gate.invalidate(2)
        gate.invalidate(1)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }

    @Test
    fun repeatedReadsForSameProfileKeepCurrentRefreshValid() {
        val gate = PluginProfileOperationGate(initialProfileId = 1)
        val first = assertNotNull(gate.current(1))
        val second = assertNotNull(gate.current(1))

        assertTrue(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
