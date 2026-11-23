@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.aiostreams

import androidx.media3.common.VideoSize
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssVideoGeometryTest {
    @Test
    fun keepsValidSourceDimensions() {
        assertEquals(
            AssVideoDimensions(1920, 1080),
            assVideoDimensionsFromSize(VideoSize(1920, 1080)),
        )
    }

    @Test
    fun assTracksAreHiddenUntilValidVideoCoordinatesExist() {
        assertFalse(hasUsableAssVideoGeometry(0, 0))
        assertFalse(hasUsableAssVideoGeometry(1920, 0))
        assertFalse(hasUsableAssVideoGeometry(0, 1080))
        assertTrue(hasUsableAssVideoGeometry(1920, 1080))
    }

    @Test
    fun unknownOrInvalidDimensionsCannotReusePreviousVideoGeometry() {
        assertNull(assVideoDimensionsFromSize(VideoSize.UNKNOWN))
        assertNull(assVideoDimensionsFromSize(VideoSize(1920, 0)))
        assertNull(assVideoDimensionsFromSize(VideoSize(0, 1080)))
        assertNull(assVideoDimensionsFromSize(VideoSize(-1, 1080)))
    }
}
