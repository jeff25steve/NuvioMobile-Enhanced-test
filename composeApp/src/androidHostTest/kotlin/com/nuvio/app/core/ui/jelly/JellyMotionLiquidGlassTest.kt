package com.nuvio.app.core.ui.jelly

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyMotionLiquidGlassTest {
    @Test
    fun verticalMovementDoesNotEnableIconRefractionButHorizontalMovementDoes() {
        val motion = JellyMotion(0, 4).apply { resize(320f, 64f, 4) }
        motion.begin(100f, 24f)

        motion.drag(0f, 8f)
        assertFalse(motion.tabIconRefractionActive, "Vertical scrolling should keep normal icons visible")

        motion.drag(5f, 8f)
        assertTrue(motion.tabIconRefractionActive, "Horizontal movement should enable the icon lens")

        motion.finish()
        assertFalse(motion.tabIconRefractionActive, "The optional icon lens must clear after release")
    }

    @Test
    fun cancellationAndProgrammaticSelectionClearIconRefraction() {
        val motion = JellyMotion(0, 4).apply { resize(320f, 64f, 4) }
        motion.begin(100f, 24f)
        motion.drag(8f, 0f)
        assertTrue(motion.tabIconRefractionActive)

        motion.cancel(selectedIndex = 0)
        assertFalse(motion.tabIconRefractionActive)

        motion.begin(100f, 24f)
        motion.drag(8f, 0f)
        assertTrue(motion.tabIconRefractionActive)

        motion.select(2)
        assertFalse(motion.tabIconRefractionActive)
    }
}
