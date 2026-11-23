package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PlaybackDataSourceCloseTest {
    @Test
    fun waitsForReaderAndPropagatesUpstreamCloseFailure() {
        val closeFailure = IllegalStateException("upstream close failed")
        var readerWaited = false

        val thrown = assertFailsWith<IllegalStateException> {
            closePlaybackDataSource(
                closeUpstream = { throw closeFailure },
                awaitReader = { readerWaited = true },
            )
        }

        assertSame(closeFailure, thrown)
        assertEquals(true, readerWaited)
    }

    @Test
    fun readerWaitFailureRetainsUpstreamCloseFailureAsSuppressed() {
        val closeFailure = IllegalStateException("upstream close failed")
        val waitFailure = IllegalStateException("reader wait failed")

        val thrown = assertFailsWith<IllegalStateException> {
            closePlaybackDataSource(
                closeUpstream = { throw closeFailure },
                awaitReader = { throw waitFailure },
            )
        }

        assertSame(waitFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }
}
