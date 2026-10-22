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
    fun explicitDisablePreservesPlacementAndUserScale() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Left,
                scaleMultiplier = 1.2f,
            ),
        )

        controller.disable()

        assertFalse(controller.state.value.enabled)
        assertEquals(OneHandedModePlacement.Left, controller.state.value.placement)
        assertEquals(1.2f, controller.state.value.scaleMultiplier)
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
        assertEquals(0.78f, oneHandedScaleForViewport(320f, 568f))
        assertEquals(0.78f, oneHandedScaleForViewport(360f, 800f))
        assertEquals(0.78f, oneHandedScaleForViewport(390f, 844f))
        assertEquals(0.6333333f, oneHandedScaleForViewport(800f, 1200f), 0.001f)
        assertEquals(0.475f, oneHandedScaleForViewport(1200f, 1600f), 0.001f)
    }

    @Test
    fun horizontalOffsetTracksLeftRightAndIntermediateAnchors() {
        assertEquals(0f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0f))
        assertEquals(340f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 1f))
        assertEquals(170f, oneHandedHorizontalOffsetPx(1000f, 0.66f, 0.5f))
    }

    @Test
    fun scaleMultiplierIsClampedToTheSupportedRange() {
        assertEquals(OneHandedMinimumScaleMultiplier, normalizedOneHandedScaleMultiplier(-10f))
        assertEquals(OneHandedMaximumScaleMultiplier, normalizedOneHandedScaleMultiplier(10f))
        assertEquals(1f, normalizedOneHandedScaleMultiplier(Float.NaN))
        assertEquals(1f, normalizedOneHandedScaleMultiplier(Float.POSITIVE_INFINITY))
    }

    @Test
    fun controllerUpdatesScaleMultiplierWithoutDisablingModeOrChangingPlacement() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true, placement = OneHandedModePlacement.Left),
        )

        controller.updateScaleMultiplier(1.2f)

        assertTrue(controller.state.value.enabled)
        assertEquals(OneHandedModePlacement.Left, controller.state.value.placement)
        assertEquals(1.2f, controller.state.value.scaleMultiplier)
    }

    @Test
    fun viewportScaleChangesContinuouslyWithTheMultiplierWithinSafeBounds() {
        val baseScale = oneHandedScaleForViewport(393f, 852f)
        val smallerScale = oneHandedScaleForViewport(393f, 852f, 0.7f)
        val largerScale = oneHandedScaleForViewport(393f, 852f, 1.2f)

        assertTrue(smallerScale < baseScale)
        assertTrue(largerScale > baseScale)
        assertTrue(largerScale <= OneHandedMaximumViewportScale)
        assertTrue(smallerScale >= OneHandedMinimumViewportScale)
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
