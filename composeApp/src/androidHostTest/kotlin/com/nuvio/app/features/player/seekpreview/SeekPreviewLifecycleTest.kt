package com.nuvio.app.features.player.seekpreview

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekPreviewLifecycleTest {
    @Test
    fun closedFeedSkipsThumbnailDecode() {
        var decodeStarted = false

        val bitmap = decodeSeekPreviewThumbnailWhileOpen(
            isClosed = { true },
            decode = { decodeStarted = true; "bitmap" },
        )

        assertNull(bitmap)
        assertFalse(decodeStarted)
    }

    @Test
    fun thumbnailDecodedDuringCloseIsDiscarded() {
        var closed = false

        val bitmap = decodeSeekPreviewThumbnailWhileOpen(
            isClosed = { closed },
            decode = { closed = true; "bitmap" },
        )

        assertNull(bitmap)
    }

    @Test
    fun cancellationFromAnInvalidatedSourceDoesNotEndThePreviewWorker() {
        assertTrue(isSeekPreviewSourceCancellationStale(generation = 2, currentGeneration = 3))
        assertFalse(isSeekPreviewSourceCancellationStale(generation = 3, currentGeneration = 3))
    }

    @Test
    fun releasedOrDisposedSourceGenerationCannotPublishAFrame() {
        assertTrue(canUseSeekPreviewSource(generation = 4, currentGeneration = 4, disposed = false))
        assertFalse(canUseSeekPreviewSource(generation = 4, currentGeneration = 5, disposed = false))
        assertFalse(canUseSeekPreviewSource(generation = 4, currentGeneration = 4, disposed = true))
    }

    @Test
    fun cancellingPendingFrameUnblocksWaiterAndClearsPendingReference() {
        val future = CompletableFuture<String>()
        val pending = AtomicReference<CompletableFuture<String>?>(future)

        cancelPendingSeekPreviewFrame(pending)

        assertTrue(future.isCancelled)
        assertNull(pending.get())
        assertFailsWith<CancellationException> { future.get() }
    }
}
