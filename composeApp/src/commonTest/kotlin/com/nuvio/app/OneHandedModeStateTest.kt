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
    fun placementToggleSwitchesSidesWithoutDisablingMode() {
        val enabled = OneHandedModeState(enabled = true, placement = OneHandedModePlacement.Right)

        val movedLeft = enabled.togglePlacement()
        val movedRight = movedLeft.togglePlacement()

        assertTrue(movedLeft.enabled)
        assertEquals(OneHandedModePlacement.Left, movedLeft.placement)
        assertTrue(movedRight.enabled)
        assertEquals(OneHandedModePlacement.Right, movedRight.placement)
    }

    @Test
    fun controllerPublishesPlacementChangesToSharedState() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true, placement = OneHandedModePlacement.Right),
        )

        controller.togglePlacement()

        assertEquals(OneHandedModePlacement.Left, controller.state.value.placement)
        assertTrue(controller.state.value.enabled)
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
    @Test
    fun responsiveScaleRespectsMinimumOnLargeScreens() {
        assertEquals(0.66f, oneHandedScaleForViewport(360f, 800f))
        assertEquals(0.6333333f, oneHandedScaleForViewport(800f, 1200f), 0.001f)
        assertEquals(0.60f, oneHandedScaleForViewport(1200f, 1600f), 0.001f)
    }

    @Test
    fun horizontalOffsetTracksLeftRightAndIntermediateAnchors() {
        assertEquals(0f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0f))
        assertEquals(340f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 1f))
        assertEquals(170f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0.5f))
    }
}
