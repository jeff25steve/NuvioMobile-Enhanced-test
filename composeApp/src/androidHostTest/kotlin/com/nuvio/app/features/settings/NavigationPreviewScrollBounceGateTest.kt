package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationPreviewScrollBounceGateTest {
    @Test
    fun `bounces once when leaving top and once when returning to top`() {
        val gate = NavigationPreviewScrollBounceGate()

        assertNull(gate.onScrollabilityChanged(canScrollBackward = false))
        assertEquals(1f, gate.onScrollabilityChanged(canScrollBackward = true))
        assertNull(gate.onScrollabilityChanged(canScrollBackward = true))
        assertNull(gate.onScrollabilityChanged(canScrollBackward = true))

        assertEquals(-1f, gate.onScrollabilityChanged(canScrollBackward = false))
        assertNull(gate.onScrollabilityChanged(canScrollBackward = false))
        assertEquals(1f, gate.onScrollabilityChanged(canScrollBackward = true))
    }
}
