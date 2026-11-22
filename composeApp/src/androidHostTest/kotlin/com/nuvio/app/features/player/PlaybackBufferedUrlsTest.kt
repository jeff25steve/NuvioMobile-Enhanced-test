package com.nuvio.app.features.player

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackBufferedUrlsTest {
    @Test
    fun progressiveAudioIsBufferedWhenVideoIsHls() {
        assertEquals(
            setOf("https://cdn.example/audio.mp4"),
            playbackBufferedUrls(
                videoUrl = "https://cdn.example/video/index.m3u8",
                audioUrl = "https://cdn.example/audio.mp4",
                videoResponseHeaders = emptyMap(),
                videoStreamType = "hls",
            ),
        )
    }

    @Test
    fun adaptiveAudioIsNotBufferedWhenVideoIsProgressive() {
        assertEquals(
            setOf("https://cdn.example/video.mp4"),
            playbackBufferedUrls(
                videoUrl = "https://cdn.example/video.mp4",
                audioUrl = "https://cdn.example/audio/index.m3u8",
                videoResponseHeaders = emptyMap(),
                videoStreamType = null,
            ),
        )
    }

    @Test
    fun videoResponseHeadersDoNotOverrideAudioUrlClassification() {
        assertEquals(
            setOf("https://cdn.example/audio.mp4"),
            playbackBufferedUrls(
                videoUrl = "https://cdn.example/video/opaque?id=1",
                audioUrl = "https://cdn.example/audio.mp4",
                videoResponseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
                videoStreamType = null,
            ),
        )
    }

    @Test
    fun opaqueAudioUrlIsNotAssumedToBeProgressive() {
        assertEquals(
            setOf("https://cdn.example/video.mp4"),
            playbackBufferedUrls(
                videoUrl = "https://cdn.example/video.mp4",
                audioUrl = "https://cdn.example/audio?id=opaque",
                videoResponseHeaders = emptyMap(),
                videoStreamType = null,
            ),
        )
    }

    @Test
    fun blankOrMissingAudioDoesNotAddAnEmptyBufferKey() {
        assertEquals(
            setOf("https://cdn.example/video.mp4"),
            playbackBufferedUrls(
                videoUrl = "https://cdn.example/video.mp4",
                audioUrl = "  ",
                videoResponseHeaders = emptyMap(),
                videoStreamType = null,
            ),
        )
    }
}
