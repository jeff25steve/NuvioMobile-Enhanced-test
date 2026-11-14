package com.nuvio.app.features.player

import android.view.PixelCopy
import com.nuvio.app.core.ui.downsampleArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class PlayerLiquidGlassCaptureLifecycleTest {
    @Test
    fun backdropDownsamplingPreservesAspectRatioAndSamplesEvenly() {
        val sampled = downsampleArgb(
            pixels = IntArray(8) { it },
            width = 4,
            height = 2,
            targetWidth = 2,
        )

        assertEquals(2, sampled?.width)
        assertEquals(1, sampled?.height)
        assertEquals(listOf(5, 7), sampled?.pixels?.toList())
    }

    @Test
    fun backdropDownsamplingRejectsInvalidDimensionsAndShortPixelBuffers() {
        assertNull(downsampleArgb(intArrayOf(1), width = 2, height = 2, targetWidth = 1))
        assertNull(downsampleArgb(intArrayOf(1), width = 1, height = 1, targetWidth = 0))
    }

    @Test
    fun successfulPixelCopyIsAcceptedEvenWhenTheCopiedFrameIsBlack() {
        assertTrue(isSuccessfulVideoFrameCopy(PixelCopy.SUCCESS))
    }

    @Test
    fun failedPixelCopyStatusesRemainUnavailable() {
        assertFalse(isSuccessfulVideoFrameCopy(PixelCopy.ERROR_SOURCE_NO_DATA))
        assertFalse(isSuccessfulVideoFrameCopy(PixelCopy.ERROR_SOURCE_INVALID))
        assertFalse(isSuccessfulVideoFrameCopy(PixelCopy.ERROR_TIMEOUT))
    }

    @Test
    fun captureBacksOffOnlyAfterConsecutiveUnavailableFrames() {
        val policy = VideoFrameCapturePolicy(unavailableFrameLimit = 3)

        assertEquals(33L, policy.delayMs)
        repeat(2) {
            policy.recordUnavailableFrame()
        }
        assertFalse(policy.shouldUseSlowRetry)
        assertEquals(33L, policy.delayMs)

        policy.recordUnavailableFrame()
        assertTrue(policy.shouldUseSlowRetry)
        assertEquals(1_000L, policy.delayMs)
    }

    @Test
    fun usableFrameImmediatelyRestoresResponsiveCaptureRate() {
        val policy = VideoFrameCapturePolicy(unavailableFrameLimit = 3)
        repeat(3) {
            policy.recordUnavailableFrame()
        }
        assertTrue(policy.shouldUseSlowRetry)

        policy.recordUsableFrame()

        assertFalse(policy.shouldUseSlowRetry)
        assertEquals(33L, policy.delayMs)
    }

    @Test
    fun repeatedUnavailableFramesDoNotOverflowTheBackoffCounter() {
        val policy = VideoFrameCapturePolicy(unavailableFrameLimit = 2)
        repeat(100_000) {
            policy.recordUnavailableFrame()
        }

        assertTrue(policy.shouldUseSlowRetry)
        assertEquals(1_000L, policy.delayMs)
    }

    @Test
    fun backdropConsumerLeasesAreReferenceCountedAndNeverNegative() {
        val backdrop = VideoFrameBackdrop()

        assertEquals(0, backdrop.consumers)
        backdrop.acquire()
        backdrop.acquire()
        assertEquals(2, backdrop.consumers)

        backdrop.release()
        assertEquals(1, backdrop.consumers)
        backdrop.release()
        assertEquals(0, backdrop.consumers)
        backdrop.release()
        assertEquals(0, backdrop.consumers)
    }
}
