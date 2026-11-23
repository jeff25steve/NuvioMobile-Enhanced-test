package com.nuvio.app.features.player.aiostreams

import androidx.media3.common.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssVideoGeometryTest {
    @Test
    fun keepsValidSourceDimensions() {
        assertEquals(
            AssVideoDimensions(1920, 1080),
            assVideoDimensionsFromSize(VideoSize(1920, 1080)),
        )
    }

    @Test
    fun unknownOrInvalidDimensionsCannotReusePreviousVideoGeometry() {
        assertNull(assVideoDimensionsFromSize(VideoSize.UNKNOWN))
        assertNull(assVideoDimensionsFromSize(VideoSize(1920, 0)))
        assertNull(assVideoDimensionsFromSize(VideoSize(0, 1080)))
        assertNull(assVideoDimensionsFromSize(VideoSize(-1, 1080)))
    }
}
