package com.nuvio.app.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsHeaderShapeTest {
    @Test
    fun settingsHeaderBackgroundDrawBoundsUseTheScreenContentInsets() {
        val bounds = screenHeaderBackgroundDrawBounds(
            widthPx = 320f,
            heightPx = 100f,
            horizontalInsetPx = 16f,
            bottomInsetPx = 0f,
        )

        assertEquals(Rect(16f, 0f, 304f, 100f), bounds)
    }

    @Test
    fun settingsHeaderBackgroundDrawBoundsRespectBottomInsetAndRejectEmptyRects() {
        val bounds = screenHeaderBackgroundDrawBounds(
            widthPx = 320f,
            heightPx = 100f,
            horizontalInsetPx = 16f,
            bottomInsetPx = 12f,
        )

        assertEquals(Rect(16f, 0f, 304f, 88f), bounds)
        assertNull(screenHeaderBackgroundDrawBounds(20f, 100f, 16f, 0f))
        assertNull(screenHeaderBackgroundDrawBounds(320f, 100f, 16f, 100f))
    }

    @Test
    fun settingsGlassHeaderUsesRectangleRegardlessOfConfiguredCornerShape() {
        val configuredRoundedShape: Shape = RoundedCornerShape(32.dp)

        val resolved = settingsHeaderShape(
            settingsGlassEnabled = true,
            defaultShape = configuredRoundedShape,
        )

        assertSame(RectangleShape, resolved)
        assertTrue(resolved !== configuredRoundedShape)
    }

    @Test
    fun nonGlassHeaderKeepsItsExistingDefaultShape() {
        val defaultShape: Shape = RoundedCornerShape(12.dp)

        assertSame(
            defaultShape,
            settingsHeaderShape(settingsGlassEnabled = false, defaultShape = defaultShape),
        )
    }
}
