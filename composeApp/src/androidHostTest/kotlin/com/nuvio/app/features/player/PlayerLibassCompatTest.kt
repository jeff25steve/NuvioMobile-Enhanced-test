package com.nuvio.app.features.player

import androidx.media3.exoplayer.ExoPlayer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLibassCompatTest {
    @Test
    fun canOpenAndCloseWithoutRetainingThePlayerHandler() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(
            context = context,
        )
        try {
            assertNotNull(player.getAssHandlerCompat())
        } finally {
            player.releaseWithAssSupportCompat()
        }
        assertNull(player.getAssHandlerCompat())
    }

    @Test
    fun libassFontScaleOnlyReportsChangesWhenTheScaleActuallyChanges() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(context = context)
        try {
            val handler = player.getAssHandlerCompat() ?: error("ASS handler was not created")
            assertTrue(handler.applyAssFontScaleCompat(1.25f))
            assertFalse(handler.applyAssFontScaleCompat(1.25f))
            assertTrue(handler.applyAssFontScaleCompat(1.5f))
            assertTrue(handler.applyAssFontScaleCompat(Float.NaN))
            assertFalse(handler.applyAssFontScaleCompat(1f))
        } finally {
            player.releaseWithAssSupportCompat()
        }
    }

    @Test
    fun exoLibassFontScalePreservesTheFullSixtySpRange() {
        val sixtySpScale = SubtitleStyleState(fontSizeSp = 60).assScale.toFloat()
        assertEquals(60f / 18f, normalizedAssFontScale(sixtySpScale), 0.0001f)
        assertEquals(4f, normalizedAssFontScale(5f))
        assertEquals(1f, normalizedAssFontScale(Float.NaN))
    }

    @Test
    fun androidMpvSubtitleSizeMapsSixtySpWithoutClampingToTheOldFortySpMaximum() {
        assertEquals(36, SubtitleStyleState(fontSizeSp = 12).toMpvSubtitleFontSize())
        assertEquals(183, SubtitleStyleState(fontSizeSp = 60).toMpvSubtitleFontSize())
    }

    @Test
    fun standardPlayerStillClosesWithoutAnAssHandler() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        assertNull(player.getAssHandlerCompat())
        player.releaseWithAssSupportCompat()
    }
}
