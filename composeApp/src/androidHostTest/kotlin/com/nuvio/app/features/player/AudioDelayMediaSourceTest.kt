package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDelayMediaSourceTest {
    @Test
    fun onlyShiftsVideoWhenAnAudioTrackIsSelected() {
        assertTrue(hasSelectedAudioTrack(listOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC)))
        assertFalse(hasSelectedAudioTrack(listOf(MimeTypes.VIDEO_H264)))
        assertFalse(hasSelectedAudioTrack(emptyList()))
    }

    @Test
    fun audioDelayIsDisabledWhenNoAudioTrackIsSelected() {
        assertEquals(125_000L, effectiveAudioDelayUs(hasSelectedAudioTrack = true, configuredDelayUs = 125_000L))
        assertEquals(0L, effectiveAudioDelayUs(hasSelectedAudioTrack = false, configuredDelayUs = 125_000L))
    }

    @Test
    fun shiftsOrdinaryVideoSampleTimestampByConfiguredAudioDelay() {
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
            timeUs = 1_000_000L
        }

        shiftVideoTimestampForAudioDelay(buffer, 125_000L)

        assertEquals(875_000L, buffer.timeUs)
    }

    @Test
    fun doesNotShiftEndOfStreamBufferTimestamp() {
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
            timeUs = Long.MIN_VALUE
            addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        }

        shiftVideoTimestampForAudioDelay(buffer, 125_000L)

        assertEquals(Long.MIN_VALUE, buffer.timeUs)
    }

    @Test
    fun doesNotShiftUnsetSampleTimestamp() {
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
            timeUs = C.TIME_UNSET
        }

        shiftVideoTimestampForAudioDelay(buffer, 125_000L)

        assertEquals(C.TIME_UNSET, buffer.timeUs)
    }
}
