package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddonProfileOperationGateTest {
    @Test
    fun overlappingAccountCleanupHoldsRemainClosedUntilAllFinish() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        gate.beginAccountCleanup()
        gate.beginAccountCleanup()

        gate.endAccountCleanup()
        assertTrue(gate.current(1) == null)

        gate.endAccountCleanup()
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }

    @Test
    fun failedCleanupStartRollsBackItsGateDepth() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val failure = IllegalStateException("simulated cancellation callback failure")

        val thrown = assertFailsWith<IllegalStateException> {
            gate.beginAccountCleanup {
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertNotNull(gate.current(1))
    }

    @Test
    fun failedNestedCleanupStartPreservesTheExistingCleanupLease() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        gate.beginAccountCleanup()
        val failure = IllegalStateException("simulated nested cleanup failure")

        kotlin.test.assertFailsWith<IllegalStateException> {
            gate.beginAccountCleanup {
                throw failure
            }
        }

        assertTrue(gate.current(1) == null)
        gate.endAccountCleanup()
        assertNotNull(gate.current(1))
    }

    @Test
    fun accountCleanupBlocksNewWorkUntilCleanupFinishes() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val old = assertNotNull(gate.current(1))
        gate.beginAccountCleanup()

        var published = false
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.current(1) == null)
        assertFalse(gate.runIfCurrent(old) { published = true })
        assertFalse(published)

        gate.endAccountCleanup()
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }


    @Test
    fun switchingProfilesInvalidatesEarlierOperations() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val primary = assertNotNull(gate.current(1))

        gate.invalidate(2)

        assertFalse(gate.isCurrent(primary))
        assertTrue(gate.isCurrent(assertNotNull(gate.current(2))))
    }

    @Test
    fun staleOperationCannotPublishAfterProfileChanges() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val primary = assertNotNull(gate.current(1))
        var published = false

        gate.invalidate(2)

        assertFalse(gate.runIfCurrent(primary) { published = true })
        assertFalse(published)
    }

    @Test
    fun repeatedCurrentProfileReadsDoNotInvalidateAnOperation() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val first = assertNotNull(gate.current(1))
        val second = assertNotNull(gate.current(1))

        assertTrue(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun switchingAwayAndBackDoesNotReviveAnOldOperation() {
        val gate = AddonProfileOperationGate(initialProfileId = 1)
        val firstPrimary = assertNotNull(gate.current(1))

        gate.invalidate(2)
        gate.invalidate(1)

        assertFalse(gate.isCurrent(firstPrimary))
        assertTrue(gate.isCurrent(assertNotNull(gate.current(1))))
    }
}
