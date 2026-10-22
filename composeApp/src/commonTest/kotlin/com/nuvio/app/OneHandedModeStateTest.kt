package com.nuvio.app

import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.TabsRoute
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
    fun playerEntryDisablesModeAndNeverRequestsAutomaticRestore() {
        val enabled = OneHandedModeState(enabled = true)

        val afterPlayerEntry = enabled.disableForPlayerEntry()

        assertFalse(afterPlayerEntry.enabled)
        assertFalse(afterPlayerEntry.disableForPlayerEntry().enabled)
    }
    @Test
    fun compactPhonesKeepMoreUsableScaleWhileLargeScreensStayCapped() {
        assertEquals(0.72f, oneHandedScaleForViewport(320f, 568f))
        assertEquals(0.72f, oneHandedScaleForViewport(360f, 800f))
        assertEquals(0.66f, oneHandedScaleForViewport(390f, 844f))
        assertEquals(0.6333333f, oneHandedScaleForViewport(800f, 1200f), 0.001f)
        assertEquals(0.60f, oneHandedScaleForViewport(1200f, 1600f), 0.001f)
    }

    @Test
    fun horizontalOffsetTracksLeftRightAndIntermediateAnchors() {
        assertEquals(0f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0f))
        assertEquals(340f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 1f))
        assertEquals(170f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0.5f))
    }

    @Test
    fun playerRouteActivatesTheNormalPlayerPresentationGuard() {
        assertTrue(
            isPlayerPresentationActive(
                currentRoute = PlayerRoute(launchId = 42L, title = "Test player"),
                visiblePlayerEntries = 0,
                streamLandscapeLoadingVisible = false,
            ),
        )
    }

    @Test
    fun hostedPlayerAndLandscapeLoadingActivateThePresentationGuard() {
        assertTrue(
            isPlayerPresentationActive(
                currentRoute = TabsRoute,
                visiblePlayerEntries = 1,
                streamLandscapeLoadingVisible = false,
            ),
        )
        assertTrue(
            isPlayerPresentationActive(
                currentRoute = TabsRoute,
                visiblePlayerEntries = 0,
                streamLandscapeLoadingVisible = true,
            ),
        )
        assertFalse(
            isPlayerPresentationActive(
                currentRoute = TabsRoute,
                visiblePlayerEntries = 0,
                streamLandscapeLoadingVisible = false,
            ),
        )
    }

    @Test
    fun activePlayerPresentationDisablesModeAndPreservesPlacement() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Left,
            ),
        )

        controller.disableForPlayerPresentationIfActive(isActive = true)

        assertFalse(controller.state.value.enabled)
        assertEquals(OneHandedModePlacement.Left, controller.state.value.placement)
    }

    @Test
    fun inactivePlayerPresentationDoesNotChangeOneHandedMode() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Right,
            ),
        )

        controller.disableForPlayerPresentationIfActive(isActive = false)

        assertTrue(controller.state.value.enabled)
        assertEquals(OneHandedModePlacement.Right, controller.state.value.placement)
    }

}
