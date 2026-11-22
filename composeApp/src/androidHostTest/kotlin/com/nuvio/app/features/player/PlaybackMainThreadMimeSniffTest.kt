package com.nuvio.app.features.player

import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackMainThreadMimeSniffTest {
    @Test
    fun localContentSniffingIsNotRunSynchronouslyOnTheMainThread() {
        assertFalse(shouldSniffLocalMimeTypeOnCurrentThread())
    }

    @Test
    fun localContentSniffingRemainsAvailableOnBackgroundThreads() {
        var shouldSniff = false
        val thread = Thread { shouldSniff = shouldSniffLocalMimeTypeOnCurrentThread() }
        thread.start()
        thread.join()
        kotlin.test.assertTrue(shouldSniff)
    }
}
