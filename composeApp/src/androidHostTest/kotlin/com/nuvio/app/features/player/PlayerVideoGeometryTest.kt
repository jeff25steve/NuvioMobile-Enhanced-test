package com.nuvio.app.features.player

import androidx.media3.common.text.Cue
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
        val bounds = requireNotNull(
            videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fit),
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(1f, bounds.right, 0.0001f)
        assertEquals(0.1217f, bounds.top, 0.001f)
        assertEquals(0.8783f, bounds.bottom, 0.001f)
    }

    @Test
    fun fillModeUsesEntireViewportInsteadOfFittingSubtitleBitmapsIntoLetterboxBounds() {
        val bounds = requireNotNull(
            videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fill),
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(1f, bounds.right, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun zoomModeReturnsCoverBoundsThatExtendBeyondTheViewport() {
        val bounds = requireNotNull(
            videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Zoom),
        )

        assertEquals(-0.1609f, bounds.left, 0.001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(1.1609f, bounds.right, 0.001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun aspectRatioWithinMedia3ToleranceKeepsFullFrameBounds() {
        val bounds = requireNotNull(
            videoDisplayBoundsFraction(1780f, 1000f, 1.77f, PlayerResizeMode.Fit),
        )

        assertEquals(0f, bounds.left, 0.0001f)
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
    fun zoomUsesExpandedVideoFrameHeightForPipSubtitleScale() {
        assertEquals(1440f, displayedVideoHeightPx(1920f, 1440f, 4f / 3f, PlayerResizeMode.Zoom))
    }

    @Test
    fun bitmapCueCoordinatesAreMappedIntoFittedVideoBounds() {
        val bounds = requireNotNull(videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fit))
        val cue = Cue.Builder()
            .setPosition(0.5f)
            .setSize(0.8f)
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION)
            .setBitmapHeight(0.2f)
            .build()

        val mapped = remapBitmapCueToVideoBounds(cue, bounds)

        assertEquals(0.5f, mapped.position, 0.0001f)
        assertEquals(0.8f, mapped.size, 0.0001f)
        assertEquals(bounds.top + 0.9f * bounds.height(), mapped.line, 0.0001f)
        assertEquals(0.2f * bounds.height(), mapped.bitmapHeight, 0.0001f)
    }

    @Test
    fun bitmapCueCoordinatesExtendWithZoomCropBounds() {
        val bounds = requireNotNull(videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Zoom))
        val cue = Cue.Builder()
            .setPosition(0.25f)
            .setSize(0.5f)
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
            .setBitmapHeight(0.4f)
            .build()

        val mapped = remapBitmapCueToVideoBounds(cue, bounds)

        assertEquals(bounds.left + 0.25f * bounds.width(), mapped.position, 0.0001f)
        assertEquals(0.5f * bounds.width(), mapped.size, 0.0001f)
        assertEquals(0.5f, mapped.line, 0.0001f)
        assertEquals(0.4f, mapped.bitmapHeight, 0.0001f)
    }

    @Test
    fun fillModeLeavesBitmapCueCoordinatesUnchanged() {
        val bounds = requireNotNull(videoDisplayBoundsFraction(1920f, 1080f, 2.35f, PlayerResizeMode.Fill))
        val cue = Cue.Builder()
            .setPosition(0.3f)
            .setSize(0.7f)
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .setBitmapHeight(0.25f)
            .build()

        val mapped = remapBitmapCueToVideoBounds(cue, bounds)

        assertEquals(0.3f, mapped.position, 0.0001f)
        assertEquals(0.7f, mapped.size, 0.0001f)
        assertEquals(0.8f, mapped.line, 0.0001f)
        assertEquals(0.25f, mapped.bitmapHeight, 0.0001f)
    }




}
