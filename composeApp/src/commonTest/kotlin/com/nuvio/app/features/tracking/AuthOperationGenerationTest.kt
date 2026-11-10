package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthOperationGenerationTest {
    @Test
    fun invalidatedOperationCannotCommitLateResult() {
        val gate = AuthOperationGeneration()
        var persistedValue = "old"

        val requestGeneration = gate.advance()
        gate.advance {
            persistedValue = "deleted"
        }

        assertFalse(gate.commitIfCurrent(requestGeneration) {
            persistedValue = "late token"
        })
        assertEquals("deleted", persistedValue)
    }

    @Test
    fun currentOperationCanCommitUntilItsGenerationIsInvalidated() {
        val gate = AuthOperationGeneration()
        var persistedValue = "old"
        val generation = gate.advance()

        assertTrue(gate.commitIfCurrent(generation) {
            persistedValue = "current token"
        })
        assertEquals("current token", persistedValue)

        gate.advance()
        assertFalse(gate.commitIfCurrent(generation) {
            persistedValue = "stale overwrite"
        })
        assertEquals("current token", persistedValue)
    }

    @Test
    fun readReturnsTheGenerationAtomicallyWithItsValue() {
        val gate = AuthOperationGeneration()
        val generation = gate.advance()
        val snapshot = gate.read { it }
        assertEquals(generation, snapshot)
        assertTrue(gate.invalidateIfCurrent(snapshot) {})
        assertFalse(gate.commitIfCurrent(snapshot) {})
    }
}
