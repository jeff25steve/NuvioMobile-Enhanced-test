package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NativeReadAheadAllocationCleanupTest {
    @Test
    fun cleanupRunsWhenAllocationFails() {
        var cleaned = false
        val failure = IllegalStateException("mapping failed")

        val thrown = assertFailsWith<IllegalStateException> {
            createWithCleanupOnFailure(
                cleanup = { cleaned = true },
                create = { throw failure },
            )
        }

        assertSame(failure, thrown)
        assertEquals(true, cleaned)
    }

    @Test
    fun cleanupFailureDoesNotReplaceOriginalAllocationFailure() {
        val allocationFailure = IllegalStateException("mapping failed")

        val thrown = assertFailsWith<IllegalStateException> {
            createWithCleanupOnFailure(
                cleanup = { throw IllegalArgumentException("cleanup failed") },
                create = { throw allocationFailure },
            )
        }

        assertSame(allocationFailure, thrown)
        assertEquals("cleanup failed", thrown.suppressed.single().message)
    }

    @Test
    fun successfulAllocationDoesNotRunCleanup() {
        var cleaned = false

        val value = createWithCleanupOnFailure(
            cleanup = { cleaned = true },
            create = { "mapped" },
        )

        assertEquals("mapped", value)
        assertEquals(false, cleaned)
    }
    @Test
    fun sharedMemoryDescriptorClosesEvenWhenUnmappingFails() {
        var closed = false
        val unmapFailure = IllegalStateException("unmap failed")

        val thrown = assertFailsWith<IllegalStateException> {
            closeMappedSharedMemory(
                unmap = { throw unmapFailure },
                close = { closed = true },
            )
        }

        assertSame(unmapFailure, thrown)
        assertEquals(true, closed)
    }

    @Test
    fun sharedMemoryCloseFailureRetainsUnmapFailure() {
        val unmapFailure = IllegalStateException("unmap failed")
        val closeFailure = IllegalArgumentException("descriptor close failed")

        val thrown = assertFailsWith<IllegalArgumentException> {
            closeMappedSharedMemory(
                unmap = { throw unmapFailure },
                close = { throw closeFailure },
            )
        }

        assertSame(closeFailure, thrown)
        assertSame(unmapFailure, thrown.suppressed.single())
    }

}
