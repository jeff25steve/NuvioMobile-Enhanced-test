package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertSame

class SettingsHeaderShapeTest {
    @Test
    fun settingsGlassHeaderUsesRectangleRegardlessOfConfiguredCornerShape() {
        val configuredRoundedShape: Shape = RoundedCornerShape(32.dp)

        assertSame(
            RectangleShape,
            settingsHeaderShape(settingsGlassEnabled = true, defaultShape = configuredRoundedShape),
        )
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
