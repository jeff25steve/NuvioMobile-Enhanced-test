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
    fun cancellingPendingFrameUnblocksWaiterAndClearsPendingReference() {
        val future = CompletableFuture<String>()
        val pending = AtomicReference<CompletableFuture<String>?>(future)

        cancelPendingSeekPreviewFrame(pending)

        assertTrue(future.isCancelled)
        assertNull(pending.get())
        assertFailsWith<CancellationException> { future.get() }
    }
}
