@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.MediaItem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPlaybackSourceTest {
    @Test
    fun fileUrisUseLocalPlaybackBufferPolicy() {
        assertTrue(MediaItem.fromUri(Uri.parse("file:///storage/emulated/0/Movies/movie.mp4")).isLocalFileSource())
    }

    @Test
    fun contentUrisUseLocalPlaybackBufferPolicyIncludingDownloadsProvider() {
        assertTrue(MediaItem.fromUri(Uri.parse("content://downloads/my_downloads/42")).isLocalFileSource())
    }

    @Test
    fun networkUrisDoNotUseLocalPlaybackBufferPolicy() {
        assertFalse(MediaItem.fromUri(Uri.parse("https://cdn.example/movie.mp4")).isLocalFileSource())
    }
}
