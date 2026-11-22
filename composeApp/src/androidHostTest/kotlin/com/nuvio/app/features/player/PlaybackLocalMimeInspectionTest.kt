package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLocalMimeInspectionTest {
    @Test
    fun knownProgressiveFileUrisSkipSynchronousPayloadInspection() {
        assertFalse(shouldInspectLocalContentMime("file:///storage/emulated/0/Movies/movie.mp4", MimeTypes.VIDEO_MP4))
        assertFalse(shouldInspectLocalContentMime("content://downloads/my_downloads/movie.mkv", MimeTypes.VIDEO_MATROSKA))
    }

    @Test
    fun adaptiveFileUrisKeepPayloadInspectionToCorrectMisleadingExtensions() {
        assertTrue(shouldInspectLocalContentMime("file:///storage/emulated/0/Movies/movie.m3u8", MimeTypes.APPLICATION_M3U8))
        assertTrue(shouldInspectLocalContentMime("content://downloads/my_downloads/movie.mpd", MimeTypes.APPLICATION_MPD))
        assertTrue(shouldInspectLocalContentMime("file:///storage/emulated/0/Movies/movie.ism", MimeTypes.APPLICATION_SS))
    }

    @Test
    fun unknownLocalExtensionsKeepPayloadInspection() {
        assertTrue(shouldInspectLocalContentMime("content://downloads/my_downloads/42", null))
    }

    @Test
    fun networkUrisNeverUseLocalPayloadInspection() {
        assertFalse(shouldInspectLocalContentMime("https://cdn.example/movie.mp4", MimeTypes.VIDEO_MP4))
        assertFalse(shouldInspectLocalContentMime("https://cdn.example/manifest.m3u8", MimeTypes.APPLICATION_M3U8))
    }
}
