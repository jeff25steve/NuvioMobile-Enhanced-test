package com.nuvio.app.features.addons

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonResponseBodyReaderTest {
    @Test
    fun readsOnlyTheConfiguredLimitAndDetectsRemainingBytes() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully("abcdefgh".encodeToByteArray())
        channel.close()

        val result = readAddonResponseBodyAtMost(channel, maxBytes = 4)

        assertContentEquals("abcd".encodeToByteArray(), result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun growsItsBufferOnlyAsTheResponseArrives() = runTest {
        val responseBytes = ByteArray(24 * 1024) { (it % 251).toByte() }
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully(responseBytes)
        channel.close()

        val limit = 20 * 1024
        val result = readAddonResponseBodyAtMost(channel, maxBytes = limit)

        assertContentEquals(responseBytes.copyOf(limit), result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun exactLengthResponseIsNotMarkedTruncated() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully("abcd".encodeToByteArray())
        channel.close()

        val result = readAddonResponseBodyAtMost(channel, maxBytes = 4)

        assertContentEquals("abcd".encodeToByteArray(), result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun zeroLimitRetainsNoBytesButStillDetectsNonEmptyResponse() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully("a".encodeToByteArray())
        channel.close()

        val result = readAddonResponseBodyAtMost(channel, maxBytes = 0)

        assertContentEquals(byteArrayOf(), result.bytes)
        assertTrue(result.truncated)
    }

    @Test
    fun emptyResponseWithZeroLimitIsNotTruncated() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.close()

        val result = readAddonResponseBodyAtMost(channel, maxBytes = 0)

        assertContentEquals(byteArrayOf(), result.bytes)
        assertFalse(result.truncated)
    }
}
