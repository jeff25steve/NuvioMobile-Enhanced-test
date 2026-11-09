package com.nuvio.app.features.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodeReleaseNotificationImageSizingTest {
    @Test
    fun `does not downsample small images`() {
        assertEquals(1, notificationBackdropSampleSize(800, 600))
    }

    @Test
    fun `bounds landscape and portrait images`() {
        assertWithinLimits(notificationBackdropSampleSize(1920, 1080), 1920, 1080)
        assertWithinLimits(notificationBackdropSampleSize(1080, 1920), 1080, 1920)
        assertWithinLimits(notificationBackdropSampleSize(2561, 1081), 2561, 1081)
    }

    @Test
    fun `bounds oversized square images`() {
        assertWithinLimits(notificationBackdropSampleSize(12000, 12000), 12000, 12000)
    }

    @Test
    fun `handles invalid source dimensions`() {
        assertEquals(1, notificationBackdropSampleSize(0, 100))
        assertEquals(1, notificationBackdropSampleSize(-1, 100))
    }

    private fun assertWithinLimits(sampleSize: Int, width: Int, height: Int) {
        assertTrue(sampleSize > 0 && sampleSize.countOneBits() == 1)
        val divisor = sampleSize.toLong()
        val w = (width.toLong() + divisor - 1L) / divisor
        val h = (height.toLong() + divisor - 1L) / divisor
        assertTrue(w <= 1280L && h <= 1280L && w * h <= 1_048_576L)
    }
}
