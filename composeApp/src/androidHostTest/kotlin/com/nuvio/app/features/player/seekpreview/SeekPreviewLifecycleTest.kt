package com.nuvio.app.features.player.seekpreview

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun exactCachedThumbnailPositionDoesNotNeedAnotherDecodeRequest() {
        assertTrue(isSeekPreviewFrameCachedAtExactPosition(12_000L, 12_000L))
    }

    @Test
    fun nearbyCachedThumbnailDoesNotSuppressARequestForTheExactPosition() {
        assertFalse(isSeekPreviewFrameCachedAtExactPosition(12_000L, 12_001L))
    }

    @Test
    fun missingCachedThumbnailDoesNotSuppressARequest() {
        assertFalse(isSeekPreviewFrameCachedAtExactPosition(null, 12_000L))
    }

    @Test
    fun queuedThumbnailWantsCoalesceToTheLatestRequestedPosition() {
        val pending = LatestSeekPreviewWant()

        pending.offer(positionMs = 1_000L, showingMs = 900L)
        pending.offer(positionMs = 2_000L, showingMs = 1_000L)
        pending.offer(positionMs = 3_000L, showingMs = 2_000L)

        assertEquals(SeekPreviewWant(3_000L, 2_000L), pending.take())
        assertNull(pending.take())
    }

    @Test
    fun clearingPendingThumbnailWantDropsStaleWorkOnClose() {
        val pending = LatestSeekPreviewWant()
        pending.offer(positionMs = 5_000L, showingMs = null)

        pending.clear()

        assertNull(pending.take())
        assertFalse(pending.hasPending())
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
