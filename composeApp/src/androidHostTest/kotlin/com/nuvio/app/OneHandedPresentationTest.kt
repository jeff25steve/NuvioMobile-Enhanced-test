package com.nuvio.app

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OneHandedPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    private var insideClickCount = 0
    private var outsideDismissCount = 0
    private var placementToggleCount = 0

    @Test
    fun tapInsideReducedSurfaceStillReachesAppContent() {
        setContent()

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.9f, size.height * 0.9f))
        }

        compose.runOnIdle {
            assertEquals(1, insideClickCount)
            assertEquals(0, outsideDismissCount)
        }
    }

    @Test
    fun placementControlReceivesTapWithoutDismissingMode() {
        setContent()

        compose.onNodeWithContentDescription("Move app to the other side").performClick()

        compose.runOnIdle {
            assertEquals(1, placementToggleCount)
            assertEquals(0, outsideDismissCount)
            assertEquals(0, insideClickCount)
        }
    }

    @Test
    fun outsideTapDismissesWithoutActivatingUnderlyingContent() {
        setContent()

        compose.onRoot().performTouchInput {
            click(Offset(size.width * 0.1f, size.height * 0.1f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
            assertEquals(1, outsideDismissCount)
        }
    }

    @Test
    fun roundedCornerOutsideSurfaceDismisses() {
        setContent()

        compose.onRoot().performTouchInput {
            val left = size.width * (1f - 0.66f)
            val top = size.height * (1f - 0.66f)
            click(Offset(left + 2f, top + 2f))
        }

        compose.runOnIdle {
            assertEquals(0, insideClickCount)
            assertEquals(1, outsideDismissCount)
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
            assertEquals(0, outsideDismissCount)
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
            controller.dismissOutside()
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

    private fun setContent() {
        insideClickCount = 0
        outsideDismissCount = 0
        placementToggleCount = 0
        compose.setContent {
            MaterialTheme {
                OneHandedPresentation(
                    enabled = true,
                    placement = OneHandedModePlacement.Right,
                    forceNormalLayout = false,
                    onOutsideTap = { outsideDismissCount++ },
                    onPlacementToggle = { placementToggleCount++ },
                    placementToggleDescription = "Move app to the other side",
                ) { contentModifier ->
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
    }
}
