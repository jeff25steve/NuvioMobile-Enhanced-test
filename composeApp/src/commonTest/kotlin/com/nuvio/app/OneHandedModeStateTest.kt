package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneHandedModeStateTest {

    @Test
    fun togglesBetweenEnabledAndDisabled() {
        val disabled = OneHandedModeState()
        val enabled = disabled.toggle()

        assertTrue(enabled.enabled)
        assertFalse(enabled.toggle().enabled)
    }

    @Test
    fun outsideTapDisablesModeWithoutChangingPlacement() {
        val enabled = OneHandedModeState(
            enabled = true,
            placement = OneHandedModePlacement.Left,
        )

        val dismissed = enabled.dismissOutside()

        assertFalse(dismissed.enabled)
        assertEquals(OneHandedModePlacement.Left, dismissed.placement)
    }

    @Test
    fun playerEntryDisablesModeAndNeverRequestsAutomaticRestore() {
        val enabled = OneHandedModeState(enabled = true)

        val afterPlayerEntry = enabled.disableForPlayerEntry()

        assertFalse(afterPlayerEntry.enabled)
        assertFalse(afterPlayerEntry.disableForPlayerEntry().enabled)
    }
}
