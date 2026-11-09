package com.nuvio.app.core.ui

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BoundedAnimatedSourceTest {
    @Test
    fun `accepts a source exactly at the configured byte limit`() = runTest {
        val expected = ByteArray(8) { it.toByte() }

        val result = readBoundedAnimatedSource(
            channel = ByteReadChannel(expected),
            maxBytes = expected.size,
        )

        assertContentEquals(expected, assertIs<BoundedAnimatedSourceRead.Bytes>(result).value)
    }

    @Test
    fun `rejects a source one byte over the configured byte limit`() = runTest {
        val result = readBoundedAnimatedSource(
            channel = ByteReadChannel(ByteArray(9)),
            maxBytes = 8,
        )

        assertSame(BoundedAnimatedSourceRead.TooLarge, result)
    }

    @Test
    fun `reports an empty response without allocating a source payload`() = runTest {
        val result = readBoundedAnimatedSource(
            channel = ByteReadChannel(ByteArray(0)),
            maxBytes = 8,
        )

        assertSame(BoundedAnimatedSourceRead.Empty, result)
    }
}
