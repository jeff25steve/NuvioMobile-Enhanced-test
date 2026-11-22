@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.swdecoder

import androidx.media3.decoder.VideoDecoderOutputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class SoftwareVideoFrameDataSpaceTest {
    @Test
    fun queuedFramesRetainTheirOwnDataSpace() {
        val firstFrame = VideoDecoderOutputBuffer { }
        val secondFrame = VideoDecoderOutputBuffer { }

        setOutputFrameDataSpace(firstFrame, 0x00123456)
        setOutputFrameDataSpace(secondFrame, 0x00654321)

        assertEquals(0x00123456, outputFrameDataSpace(firstFrame))
        assertEquals(0x00654321, outputFrameDataSpace(secondFrame))
        assertEquals(0x00123456, outputFrameDataSpace(firstFrame))
    }
}
