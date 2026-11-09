package com.nuvio.app.core.network

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BoundedImageSourceTest {
    @Test
    fun `accepts exactly the configured byte limit`() = runTest {
        val bytes = ByteArray(8) { it.toByte() }
        val result = readBoundedImageSource(ByteReadChannel(bytes), bytes.size)
        assertContentEquals(bytes, assertIs<BoundedImageSourceRead.Bytes>(result).value)
    }

    @Test
    fun `rejects a response one byte over the limit`() = runTest {
        val result = readBoundedImageSource(ByteReadChannel(ByteArray(9)), 8)
        assertSame(BoundedImageSourceRead.TooLarge, result)
    }

    @Test
    fun `handles empty responses`() = runTest {
        val result = readBoundedImageSource(ByteReadChannel(ByteArray(0)), 8)
        assertSame(BoundedImageSourceRead.Empty, result)
    }
}
