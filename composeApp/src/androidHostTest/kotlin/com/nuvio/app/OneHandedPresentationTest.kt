package com.nuvio.app

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

    private fun setContent() {
        insideClickCount = 0
        outsideDismissCount = 0
        compose.setContent {
            MaterialTheme {
                OneHandedPresentation(
                    enabled = true,
                    placement = OneHandedModePlacement.Right,
                    forceNormalLayout = false,
                    onOutsideTap = { outsideDismissCount++ },
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
