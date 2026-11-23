package com.nuvio.app.features.player

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerVideoGeometryTest {
    @Test
    fun fitModeReturnsLetterboxBoundsForUltrawideVideo() {
        val bounds = videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fit)

        assertEquals(0f, bounds!!.left, 0.0001f)
        assertEquals(1f, bounds.right, 0.0001f)
        assertEquals(0.1217f, bounds.top, 0.001f)
        assertEquals(0.8783f, bounds.bottom, 0.001f)
    }

    @Test
    fun fillModeUsesEntireViewportInsteadOfFittingSubtitleBitmapsIntoLetterboxBounds() {
        val bounds = videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fill)

        assertEquals(0f, bounds!!.left, 0.0001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(1f, bounds.right, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun zoomModeReturnsCoverBoundsThatExtendBeyondTheViewport() {
        val bounds = videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Zoom)

        assertEquals(-0.1609f, bounds!!.left, 0.001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(1.1609f, bounds.right, 0.001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun aspectRatioWithinMedia3ToleranceKeepsFullFrameBounds() {
        val bounds = videoDisplayBoundsFraction(1780f, 1000f, 1.77f, PlayerResizeMode.Fit)

        assertEquals(0f, bounds!!.left, 0.0001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(1f, bounds.right, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun invalidAspectRatioDoesNotProduceInvalidCueBounds() {
        assertNull(videoDisplayBoundsFraction(1920f, 1080f, Float.NaN, PlayerResizeMode.Fit))
        assertNull(videoDisplayBoundsFraction(1920f, 1080f, Float.POSITIVE_INFINITY, PlayerResizeMode.Zoom))
    }

    @Test
    fun fitModeUsesFittedVideoHeightForPipSubtitleScale() {
        val height = displayedVideoHeightPx(1920f, 1080f, 2.35f, PlayerResizeMode.Fit)

        assertEquals(817.02f, height!!, 0.1f)
    }

    @Test
    fun fillAndZoomModesUseTheFullViewportHeightForPipSubtitleScale() {
        assertEquals(1080f, displayedVideoHeightPx(1920f, 1080f, 2.35f, PlayerResizeMode.Fill))
        assertEquals(1080f, displayedVideoHeightPx(1920f, 1080f, 2.35f, PlayerResizeMode.Zoom))
    }

    @Test
    fun invalidPipGeometryReturnsNull() {
        assertNull(displayedVideoHeightPx(0f, 1080f, 2.35f, PlayerResizeMode.Fit))
        assertNull(displayedVideoHeightPx(1920f, 1080f, Float.NaN, PlayerResizeMode.Fit))
    }

    @Test
    fun zoomUsesViewportHeightWhenTheSourceIsNarrowerThanTheDisplay() {
        assertEquals(1080f, displayedVideoHeightPx(1920f, 1080f, 4f / 3f, PlayerResizeMode.Zoom))
    }
}
