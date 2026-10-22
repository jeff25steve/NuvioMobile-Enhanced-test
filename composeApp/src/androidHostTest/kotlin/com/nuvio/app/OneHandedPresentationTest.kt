package com.nuvio.app

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OneHandedPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    private var insideClickCount = 0
    private var placementToggleCount = 0
    private var exitCount = 0
    private var scrollState: ScrollState? = null

    @Test
    fun tapInsideReducedSurfaceStillReachesAppContent() {
        setContent()

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.9f, size.height * 0.9f))
        }

        compose.runOnIdle {
            assertEquals(1, insideClickCount)
        }
    }

    @Test
    fun forcedNormalLayoutKeepsFullScreenInteractionAndHidesPlacementControl() {
        setContent(forceNormalLayout = true)

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertEquals(1, insideClickCount)
            assertEquals(0, placementToggleCount)
        }
    }

    @Test
    fun compactViewportStillDeliversInsideTapToAppContent() {
        setContent(compactViewport = true)

        compose.onNodeWithTag("compactViewport").performTouchInput {
            click(Offset(size.width * 0.9f, size.height * 0.9f))
        }

        compose.runOnIdle {
            assertEquals(1, insideClickCount)
        }
    }

    @Test
    fun compactViewportLeftPlacementStillDeliversInsideTap() {
        setContent(compactViewport = true, placement = OneHandedModePlacement.Left)

        compose.onNodeWithTag("compactViewport").performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.9f))
        }

        compose.runOnIdle {
            assertEquals(1, insideClickCount)
        }
    }

    @Test
    fun compactViewportLeftPlacementDoesNotDismissOrActivateOutsideContent() {
        setContent(compactViewport = true, placement = OneHandedModePlacement.Left)

        compose.onNodeWithTag("compactViewport").performTouchInput {
            click(Offset(size.width * 0.9f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun compactViewportContentCanStillScroll() {
        setContent(compactViewport = true, scrollableContent = true)

        compose.onNodeWithTag("scrollableContent").performTouchInput {
            swipeUp()
        }

        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue((scrollState?.value ?: 0) > 0, "Scrolling should advance the content position")
        }
    }

    @Test
    fun compactViewportPlacementControlStillReceivesTap() {
        setContent(compactViewport = true)

        compose.onNodeWithContentDescription("Move app to the other side").performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, placementToggleCount)
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun compactViewportOutsideTapDoesNotDismissOrActivateContent() {
        setContent(compactViewport = true)

        compose.onNodeWithTag("compactViewport").performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun resizeHandleUpdatesScaleContinuouslyWithinBounds() {
        val multiplier = mutableStateOf(1f)
        compose.setContent {
            MaterialTheme {
                OneHandedPresentation(
                    enabled = true,
                    placement = OneHandedModePlacement.Right,
                    forceNormalLayout = false,
                    scaleMultiplier = multiplier.value,
                    onScaleMultiplierChange = { multiplier.value = it },
                    onPlacementToggle = {},
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize())
                }
            }
        }

        compose.onNodeWithContentDescription("Resize one-handed mode").performTouchInput {
            down(0, Offset(size.width / 2f, size.height / 2f))
            moveTo(0, Offset(size.width / 2f, size.height / 2f - 48f), delayMillis = 100)
            up(0)
        }

        compose.runOnIdle {
            assertTrue(multiplier.value > 1f, "Dragging the resize handle upward should enlarge the app")
            assertTrue(multiplier.value <= OneHandedMaximumScaleMultiplier)
        }
    }

    @Test
    fun explicitExitControlDisablesModeWithoutOutsideDismissal() {
        setContent()

        compose.onNodeWithContentDescription("Exit one-handed mode").performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, exitCount)
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun placementControlReceivesTapWithoutDismissingMode() {
        setContent()

        compose.onNodeWithContentDescription("Move app to the other side").performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, placementToggleCount)
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun outsideTapIsConsumedWithoutActivatingUnderlyingContent() {
        setContent()

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun roundedCornerOutsideTapDoesNotActivateContent() {
        setContent()

        compose.onRoot().performTouchInput {
            val compactScale = oneHandedScaleForViewport(360f, 800f)
            val left = size.width * (1f - compactScale)
            val top = size.height * (1f - compactScale)
            click(Offset(left + 2f, top + 2f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun outsideDragDoesNotDismissOrActivateContent() {
        setContent()

        compose.onRoot().performTouchInput {
            val start = Offset(size.width * 0.1f, size.height * 0.1f)
            down(0, start)
            moveTo(0, Offset(size.width * 0.2f, size.height * 0.2f), delayMillis = 100)
            up(0)
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
        }
    }


    @Test
    fun aTabMountedAfterToggleReceivesCurrentOneHandedState() {
        val controller = OneHandedModeController()
        val showSecondConsumer = mutableStateOf(false)

        compose.setContent {
            val state by controller.state.collectAsState()
            MaterialTheme {
                Column {
                    Text("first:${state.enabled}")
                    if (showSecondConsumer.value) {
                        Text("second:${state.enabled}")
                    }
                }
            }
        }

        compose.runOnIdle {
            assertEquals(1, compose.onNodeWithText("first:false").fetchSemanticsNodes().size)
            controller.toggle()
            showSecondConsumer.value = true
        }

        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, compose.onNodeWithText("first:true").fetchSemanticsNodes().size)
            assertEquals(1, compose.onNodeWithText("second:true").fetchSemanticsNodes().size)
        }

        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, compose.onNodeWithText("first:false").fetchSemanticsNodes().size)
            assertEquals(1, compose.onNodeWithText("second:false").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun playerEntryDisablesSharedControllerForEveryConsumer() {
        val controller = OneHandedModeController()
        controller.toggle()

        compose.setContent {
            val state by controller.state.collectAsState()
            MaterialTheme {
                Column {
                    Text("first:${state.enabled}")
                    Text("second:${state.enabled}")
                }
            }
        }

        compose.runOnIdle {
            assertEquals(1, compose.onNodeWithText("first:true").fetchSemanticsNodes().size)
            controller.disableForPlayerEntry()
        }

        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, compose.onNodeWithText("first:false").fetchSemanticsNodes().size)
            assertEquals(1, compose.onNodeWithText("second:false").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun initialScalePrioritizesOneHandedUsabilityAcrossPhoneSizes() {
        assertEquals(0.72f, oneHandedScaleForViewport(393f, 852f))
        assertEquals(0.76f, oneHandedScaleForViewport(320f, 568f))
        assertEquals(0.54285717f, oneHandedScaleForViewport(320f, 1400f), 0.001f)
    }

    @Test
    fun modeStartsDisabledAndToggleAlternatesEnabledState() {
        val initial = OneHandedModeState()

        assertEquals(false, initial.enabled)
        assertEquals(true, initial.toggle().enabled)
        assertEquals(false, initial.toggle().toggle().enabled)
    }

    @Test
    fun outsideDismissalDisablesModeAndPreservesPlacement() {
        val enabled = OneHandedModeState(enabled = true, placement = OneHandedModePlacement.Left)

        val dismissed = enabled.dismissOutside()

        assertEquals(false, dismissed.enabled)
        assertEquals(OneHandedModePlacement.Left, dismissed.placement)
    }

    @Test
    fun playerEntryDisablesModeAndDoesNotChangePlacement() {
        val enabled = OneHandedModeState(enabled = true, placement = OneHandedModePlacement.Left)

        val afterPlayerEntry = enabled.disableForPlayerEntry()

        assertEquals(false, afterPlayerEntry.enabled)
        assertEquals(OneHandedModePlacement.Left, afterPlayerEntry.placement)
    }

    @Test
    fun playerEntryFromDisabledStateDoesNotEnableMode() {
        val disabled = OneHandedModeState()

        assertEquals(false, disabled.disableForPlayerEntry().enabled)
    }

    private fun setContent(
        compactViewport: Boolean = false,
        scrollableContent: Boolean = false,
        forceNormalLayout: Boolean = false,
        placement: OneHandedModePlacement = OneHandedModePlacement.Right,
    ) {
        insideClickCount = 0
        placementToggleCount = 0
        exitCount = 0
        compose.setContent {
            MaterialTheme {
                if (compactViewport) {
                    Box(Modifier.size(320.dp, 568.dp).testTag("compactViewport")) {
                        presentationContent(scrollableContent, forceNormalLayout, placement)
                    }
                } else {
                    presentationContent(scrollableContent, forceNormalLayout, placement)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun presentationContent(
        scrollableContent: Boolean = false,
        forceNormalLayout: Boolean = false,
        placement: OneHandedModePlacement = OneHandedModePlacement.Right,
    ) {
        OneHandedPresentation(
            enabled = true,
            placement = placement,
            forceNormalLayout = forceNormalLayout,
            onPlacementToggle = { placementToggleCount++ },
            placementToggleDescription = "Move app to the other side",
            onExit = { exitCount++ },
            exitDescription = "Exit one-handed mode",
        ) { contentModifier ->
            if (scrollableContent) {
                val state = rememberScrollState()
                scrollState = state
                Column(
                    modifier = contentModifier
                        .fillMaxSize()
                        .verticalScroll(state)
                        .testTag("scrollableContent"),
                ) {
                    repeat(30) { index ->
                        Text("Scrollable item $index", modifier = Modifier.clickable { insideClickCount++ })
                    }
                }
            } else {
                Box(contentModifier) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { insideClickCount++ },
                    )
                }
            }
        }
    }

    @Test
    fun dialogActionReceivesTapWithoutDismissingOneHandedMode() {
        val dialogVisible = mutableStateOf(true)
        var dialogActionCount = 0

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    OneHandedPresentation(
                        enabled = true,
                        placement = OneHandedModePlacement.Right,
                        forceNormalLayout = false,
                        onPlacementToggle = {},
                        placementToggleDescription = "Move app to the other side",
                    ) { contentModifier ->
                        Box(contentModifier.fillMaxSize())
                    }

                    if (dialogVisible.value) {
                        Dialog(onDismissRequest = { dialogVisible.value = false }) {
                            Button(
                                onClick = {
                                    dialogActionCount++
                                    dialogVisible.value = false
                                },
                            ) {
                                Text("Confirm dialog action")
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Confirm dialog action").performClick()

        compose.runOnIdle {
            assertEquals(1, dialogActionCount)
        }

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
        }
    }

    @Test
    fun popupActionReceivesTapWithoutDismissingOneHandedMode() {
        val popupVisible = mutableStateOf(true)
        var popupActionCount = 0

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    OneHandedPresentation(
                        enabled = true,
                        placement = OneHandedModePlacement.Right,
                        forceNormalLayout = false,
                        onPlacementToggle = {},
                        placementToggleDescription = "Move app to the other side",
                    ) { contentModifier ->
                        Box(contentModifier.fillMaxSize())
                    }

                    if (popupVisible.value) {
                        Popup(
                            alignment = Alignment.Center,
                            onDismissRequest = { popupVisible.value = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Button(
                                onClick = {
                                    popupActionCount++
                                    popupVisible.value = false
                                },
                            ) {
                                Text("Choose popup action")
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Choose popup action").performClick()

        compose.runOnIdle {
            assertEquals(1, popupActionCount)
        }

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
        }
    }


    @Test
    fun inAppOverlayActionReceivesTapWithoutDismissingOneHandedMode() {
        var overlayActionCount = 0

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    OneHandedPresentation(
                        enabled = true,
                        placement = OneHandedModePlacement.Right,
                        forceNormalLayout = false,
                        onPlacementToggle = {},
                        placementToggleDescription = "Move app to the other side",
                    ) { contentModifier ->
                        Box(contentModifier.fillMaxSize())
                    }

                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(160.dp)
                            .clickable { overlayActionCount++ }
                            .testTag("inAppOverlayAction"),
                    ) {
                        Text("In-app overlay action")
                    }
                }
            }
        }

        compose.onNodeWithTag("inAppOverlayAction").performClick()

        compose.runOnIdle {
            assertEquals(1, overlayActionCount)
        }
    }

}
