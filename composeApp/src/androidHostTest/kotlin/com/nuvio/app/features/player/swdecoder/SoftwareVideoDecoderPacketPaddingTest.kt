@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.swdecoder

import androidx.media3.decoder.DecoderInputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftwareVideoDecoderPacketPaddingTest {
    @Test
    fun packetPaddingIsReservedAndZeroedWithoutChangingSampleLimit() {
        val input = DecoderInputBuffer(
            DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
            FFMPEG_INPUT_PADDING_SIZE,
        )
        input.ensureSpaceForWrite(3)
        val data = requireNotNull(input.data)
        data.put(byteArrayOf(0x11, 0x22, 0x33))
        input.flip()
        val sampleLimit = data.limit()

        data.limit(data.capacity())
        for (index in sampleLimit until sampleLimit + FFMPEG_INPUT_PADDING_SIZE) {
            data.put(index, 0x7F)
        }
        data.limit(sampleLimit)

        assertEquals(3, prepareFfmpegPacketData(data))
        assertEquals(sampleLimit, data.limit())
        assertEquals(0x11, data.get(0).toInt() and 0xFF)
        assertEquals(0x22, data.get(1).toInt() and 0xFF)
        assertEquals(0x33, data.get(2).toInt() and 0xFF)

        data.limit(data.capacity())
        for (index in sampleLimit until sampleLimit + FFMPEG_INPUT_PADDING_SIZE) {
            assertEquals(0, data.get(index).toInt())
        }
        data.limit(sampleLimit)
    }

    @Test
    fun rejectsBuffersWithoutFfmpegPaddingCapacity() {
        val data = java.nio.ByteBuffer.allocateDirect(3)
        data.put(byteArrayOf(1, 2, 3))
        data.flip()

        val rejected = runCatching { prepareFfmpegPacketData(data) }.isFailure
        assertTrue(rejected)
    }
}
