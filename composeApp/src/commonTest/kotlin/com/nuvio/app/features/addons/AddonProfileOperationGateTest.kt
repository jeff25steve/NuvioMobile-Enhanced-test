package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddonProfileOperationGateTest {
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
