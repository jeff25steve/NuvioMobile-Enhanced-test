package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackDataSourceSelectionTest {
    @Test
    fun progressiveSeparateAudioIsBufferedIndependentlyOfHlsVideoType() {
        val videoUrl = "https://example.com/master.m3u8"
        val audioUrl = "https://example.com/audio.mp4"

        assertEquals(
            setOf(audioUrl),
            playbackBufferedUrls(
                sourceUrl = videoUrl,
                sourceAudioUrl = audioUrl,
                responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
                streamType = "hls",
            ),
        )
    }

    @Test
    fun adaptiveSeparateAudioIsNotAddedToProgressiveBuffering() {
        val audioUrl = "https://example.com/audio.m3u8"

        assertEquals(
            emptySet(),
            playbackBufferedUrls(
                sourceUrl = "https://example.com/video.mp4",
                sourceAudioUrl = audioUrl,
                responseHeaders = emptyMap(),
                streamType = null,
            ).filterTo(mutableSetOf()) { it == audioUrl },
        )
    }

}
