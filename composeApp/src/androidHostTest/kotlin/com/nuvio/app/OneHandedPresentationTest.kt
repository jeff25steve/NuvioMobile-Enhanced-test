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
import androidx.compose.ui.semantics.SemanticsProperties
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
    fun topHandleDragMovesWindowUpWithoutResizing() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    verticalPosition = modeState.verticalPosition,
                    onVerticalPositionChange = controller::updateVerticalPosition,
                    onPlacementToggle = controller::togglePlacement,
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize())
                }
            }
        }

        compose.waitForIdle()
        val handle = compose.onNodeWithTag("oneHandedMoveWindowHandle")
        val handleNode = handle.fetchSemanticsNode()
        assertTrue(
            handleNode.config[SemanticsProperties.ContentDescription].any { it.isNotBlank() },
            "The move handle must keep a localized accessibility description",
        )
        val startingHandleTop = handleNode.boundsInRoot.top

        handle.performTouchInput {
            val start = Offset(size.width / 2f, size.height / 2f)
            down(0, start)
            moveTo(0, Offset(start.x, start.y - 48f), delayMillis = 100)
            up(0)
        }
        compose.waitForIdle()
        val finalHandleTop = handle.fetchSemanticsNode().boundsInRoot.top

        compose.runOnIdle {
            assertTrue(finalHandleTop < startingHandleTop, "The grip should settle at the new window position")
            assertTrue(controller.state.value.verticalPosition > 0f)
            assertTrue(controller.state.value.verticalPosition <= 1f)
            assertEquals(1f, controller.state.value.scaleMultiplier)
            assertEquals(OneHandedModePlacement.Right, controller.state.value.placement)
        }
    }

    @Test
    fun moveHandleAccessibilityActionsMoveWindowToTopAndBottom() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true, verticalPosition = 0f),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                Box(Modifier.size(320.dp, 568.dp)) {
                    OneHandedPresentation(
                        enabled = modeState.enabled,
                        placement = modeState.placement,
                        forceNormalLayout = false,
                        scaleMultiplier = modeState.scaleMultiplier,
                        onScaleMultiplierChange = controller::updateScaleMultiplier,
                        verticalPosition = modeState.verticalPosition,
                        onVerticalPositionChange = controller::updateVerticalPosition,
                        onResizeCommit = controller::updateResize,
                        onPlacementToggle = controller::togglePlacement,
                        placementToggleDescription = "Move app to the other side",
                    ) { contentModifier ->
                        Box(contentModifier.fillMaxSize())
                    }
                }
            }
        }

        val moveHandle = compose.onNodeWithTag("oneHandedMoveWindowHandle")
        val initialActions = moveHandle.fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        assertTrue(initialActions.any { it.label == "Move window to top" })
        assertTrue(initialActions.any { it.label == "Move window to bottom" })

        val moveToTop = initialActions.first { it.label == "Move window to top" }
        compose.runOnIdle { assertTrue(moveToTop.action()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, controller.state.value.verticalPosition) }

        val moveToBottom = moveHandle.fetchSemanticsNode()
            .config[SemanticsProperties.CustomActions]
            .first { it.label == "Move window to bottom" }
        compose.runOnIdle { assertTrue(moveToBottom.action()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, controller.state.value.verticalPosition) }
    }

    @Test
    fun resizingCornerChangesScaleWithoutMovingItsAnchor() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Right,
            ),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    verticalPosition = modeState.verticalPosition,
                    onVerticalPositionChange = controller::updateVerticalPosition,
                    onResizeCommit = controller::updateResize,
                    onPlacementToggle = controller::togglePlacement,
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize().testTag("resizeSurface"))
                }
            }
        }

        compose.waitForIdle()
        val resizeSurface = compose.onNodeWithTag("resizeSurface")
        val originalBounds = resizeSurface.fetchSemanticsNode().boundsInRoot

        val resizeHandle = compose.onNodeWithTag("oneHandedResizeHandle")
        assertTrue(
            resizeHandle.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].any { it.isNotBlank() },
            "The resize handle must keep a localized accessibility description",
        )
        resizeHandle.performTouchInput {
            val start = Offset(size.width / 2f, size.height / 2f)
            down(0, start)
            moveTo(0, Offset(start.x - 48f, start.y - 48f), delayMillis = 100)
            up(0)
        }

        compose.waitForIdle()
        val resizedBounds = resizeSurface.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle {
            assertTrue(controller.state.value.scaleMultiplier > 1f)
            assertEquals(OneHandedModePlacement.Right, controller.state.value.placement)
            assertEquals(0f, controller.state.value.verticalPosition)
            assertTrue(controller.state.value.enabled)
            assertEquals(originalBounds.right, resizedBounds.right, 1f)
            assertEquals(originalBounds.bottom, resizedBounds.bottom, 1f)
        }
    }

    @Test
    fun resizeHandleAccessibilityActionsAdjustSizeAndPreserveAnchor() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                verticalPosition = 0.35f,
                scaleMultiplier = 1f,
            ),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                Box(Modifier.size(320.dp, 568.dp)) {
                    OneHandedPresentation(
                        enabled = modeState.enabled,
                        placement = modeState.placement,
                        forceNormalLayout = false,
                        scaleMultiplier = modeState.scaleMultiplier,
                        onScaleMultiplierChange = controller::updateScaleMultiplier,
                        verticalPosition = modeState.verticalPosition,
                        onVerticalPositionChange = controller::updateVerticalPosition,
                        onResizeCommit = controller::updateResize,
                        onPlacementToggle = controller::togglePlacement,
                        placementToggleDescription = "Move app to the other side",
                    ) { contentModifier ->
                        Box(contentModifier.fillMaxSize())
                    }
                }
            }
        }

        val resizeHandle = compose.onNodeWithTag("oneHandedResizeHandle")
        val initialActions = resizeHandle.fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        assertTrue(initialActions.any { it.label == "Increase one-handed window size" })
        assertTrue(initialActions.any { it.label == "Decrease one-handed window size" })
        val increaseSize = initialActions.first { it.label == "Increase one-handed window size" }

        compose.runOnIdle { assertTrue(increaseSize.action()) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(controller.state.value.scaleMultiplier > 1f)
            assertTrue(controller.state.value.verticalPosition > 0.35f)
        }

        val decreaseSize = resizeHandle.fetchSemanticsNode()
            .config[SemanticsProperties.CustomActions]
            .first { it.label == "Decrease one-handed window size" }
        compose.runOnIdle { assertTrue(decreaseSize.action()) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1f, controller.state.value.scaleMultiplier)
            assertEquals(0.35f, controller.state.value.verticalPosition, 0.01f)
        }
    }

    @Test
    fun tappingTopHandleDoesNotExitOrChangePlacement() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Right,
            ),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    onPlacementToggle = { controller.togglePlacement() },
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize())
                }
            }
        }

        compose.onNodeWithTag("oneHandedMoveWindowHandle")
            .performTouchInput { click() }

        compose.runOnIdle {
            assertTrue(controller.state.value.enabled)
            assertEquals(OneHandedModePlacement.Right, controller.state.value.placement)
            assertEquals(1f, controller.state.value.scaleMultiplier)
        }
    }

    @Test
    fun topHandleHorizontalDragDoesNotResizeOrSwitchSide() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    verticalPosition = modeState.verticalPosition,
                    onVerticalPositionChange = controller::updateVerticalPosition,
                    onPlacementToggle = controller::togglePlacement,
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize())
                }
            }
        }

        compose.onNodeWithTag("oneHandedMoveWindowHandle").performTouchInput {
            val start = Offset(size.width / 2f, size.height / 2f)
            down(0, start)
            moveTo(0, Offset(start.x + 80f, start.y), delayMillis = 100)
            up(0)
        }

        compose.runOnIdle {
            assertTrue(controller.state.value.enabled)
            assertEquals(OneHandedModePlacement.Right, controller.state.value.placement)
            assertEquals(1f, controller.state.value.scaleMultiplier)
            assertEquals(0f, controller.state.value.verticalPosition)
        }
    }

    @Test
    fun sideArrowSwitchesPlacementWithoutDisablingMode() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(
                enabled = true,
                placement = OneHandedModePlacement.Right,
            ),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    onPlacementToggle = controller::togglePlacement,
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.fillMaxSize())
                }
            }
        }

        compose.onNodeWithContentDescription("Move app to the other side")
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(OneHandedModePlacement.Left, controller.state.value.placement)
            assertTrue(controller.state.value.enabled)
        }
    }

    @Test
    fun outsideTapKeepsModeEnabledAndDoesNotActivateUnderlyingContent() {
        val controller = OneHandedModeController(
            initialState = OneHandedModeState(enabled = true),
        )
        compose.setContent {
            val modeState by controller.state.collectAsState()
            MaterialTheme {
                OneHandedPresentation(
                    enabled = modeState.enabled,
                    placement = modeState.placement,
                    forceNormalLayout = false,
                    scaleMultiplier = modeState.scaleMultiplier,
                    onScaleMultiplierChange = controller::updateScaleMultiplier,
                    onPlacementToggle = { controller.togglePlacement() },
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
                    Box(contentModifier.clickable { insideClickCount++ }.fillMaxSize())
                }
            }
        }

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertTrue(controller.state.value.enabled)
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
        assertEquals(0.78f, oneHandedScaleForViewport(393f, 852f))
        assertEquals(0.78f, oneHandedScaleForViewport(320f, 568f))
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
