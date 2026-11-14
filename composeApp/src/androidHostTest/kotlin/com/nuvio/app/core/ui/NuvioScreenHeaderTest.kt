package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NuvioScreenHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arabicTitleUsesAvailableWidth() = assertTitleWidth(LayoutDirection.Rtl, "بحث")

    @Test fun englishTitleUsesAvailableWidth() = assertTitleWidth(LayoutDirection.Ltr, "Search")

    @Test fun edgeToEdgeHeaderExpandsBackgroundWithoutMovingContentToTheEdges() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(320.dp, 100.dp)) {
                    NuvioScreenHeader(
                        title = "Search",
                        modifier = Modifier.fillMaxWidth().testTag("header"),
                        includeStatusBarPadding = false,
                        contentHorizontalPadding = 16.dp,
                        actions = { Box(Modifier.size(40.dp).testTag("headerAction")) },
                    )
                }
            }
        }

        val headerBounds = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("Search").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithTag("headerAction").fetchSemanticsNode().boundsInRoot
        val contentInsetPx = with(compose.density) { 16.dp.toPx() }
        val parentWidthPx = with(compose.density) { 320.dp.toPx() }

        assertTrue(abs(headerBounds.width - parentWidthPx) <= 1f, "Header background container should span the parent width")
        assertTrue(abs(titleBounds.left - contentInsetPx) <= 1f, "Title should retain the search field's 16 dp inset")
        assertTrue(abs(actionBounds.right - (parentWidthPx - contentInsetPx)) <= 1f, "Action should retain the matching trailing inset")
    }

    @Test fun settingsHeaderBackgroundAlignsWithScreenContentPadding() {
        compose.setContent {
            CompositionLocalProvider(LocalNuvioScreenMetrics provides NuvioScreenMetrics(16.dp)) {
                NuvioTheme {
                    Box(
                        Modifier
                            .size(320.dp, 100.dp)
                            .background(Color.DarkGray)
                            .testTag("settingsRoot"),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            NuvioScreenHeader(
                                title = "Settings",
                                modifier = Modifier.fillMaxWidth(),
                                includeStatusBarPadding = false,
                                backgroundBrushOverride = SolidColor(Color.Magenta),
                                backgroundHorizontalInset = 16.dp,
                            )
                        }
                    }
                }
            }
        }

        val pixels = compose.onNodeWithTag("settingsRoot").captureToImage().toPixelMap()
        val insetPx = with(compose.density) { 16.dp.toPx().roundToInt() }
        val outsidePx = with(compose.density) { 8.dp.toPx().roundToInt() }
        val innerPx = with(compose.density) { 20.dp.toPx().roundToInt() }
        val rightInnerPx = with(compose.density) { 300.dp.toPx().roundToInt() }
        val rightOutsidePx = with(compose.density) { 312.dp.toPx().roundToInt() }
        val sampleY = with(compose.density) { 4.dp.toPx().roundToInt() }

        assertEquals(Color.DarkGray, pixels[outsidePx, sampleY], "Background should not extend past the Settings content's leading inset")
        assertEquals(Color.Magenta, pixels[innerPx, sampleY], "Header background should begin at the Settings content edge")
        assertEquals(Color.Magenta, pixels[rightInnerPx, sampleY], "Header background should reach the trailing content edge")
        assertEquals(Color.DarkGray, pixels[rightOutsidePx, sampleY], "Background should not extend past the Settings content's trailing inset")

        val titleBounds = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(titleBounds.left - insetPx) <= 1f, "Settings title should remain aligned with the content inset")
    }

    @Test fun titleLeavesRoomForBackButtonAndAction() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                NuvioTheme {
                    NuvioScreenHeader(
                        title = "التنزيلات",
                        modifier = Modifier.size(320.dp, 100.dp).testTag("header"),
                        includeStatusBarPadding = false,
                        onBack = {},
                        actions = { Box(Modifier.size(40.dp).testTag("headerAction")) },
                    )
                }
            }
        }
        val titleBounds = compose.onNodeWithText("التنزيلات").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithTag("headerAction").fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot

        assertTrue(
            abs(titleBounds.left - actionBounds.right) <= 1f,
            "RTL title should fill the slot up to the action",
        )
        assertTrue(
            titleBounds.right <= backBounds.left + 1f,
            "RTL title should leave the back button's space clear",
        )
    }

    private fun assertTitleWidth(direction: LayoutDirection, title: String) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NuvioTheme {
                    NuvioScreenHeader(
                        title = title,
                        modifier = Modifier.size(320.dp, 100.dp).testTag("header"),
                        includeStatusBarPadding = false,
                    )
                }
            }
        }
        val titleWidth = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.width
        val headerWidth = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            abs(titleWidth - headerWidth) <= 1f,
            "Title should use the available header width in $direction layout",
        )
    }
}
