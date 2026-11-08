package com.nuvio.app.core.network

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiHttpClientTest {
    @Test
    fun `reads an empty response`() = runTest {
        assertEquals("", readBoundedResponseBody(ByteReadChannel(ByteArray(0)), 0L, 0))
    }

    @Test
    fun `accepts a response exactly at the configured byte limit`() = runTest {
        val body = "Nuvio"
        val bytes = body.encodeToByteArray()
        assertEquals(body, readBoundedResponseBody(ByteReadChannel(bytes), bytes.size.toLong(), bytes.size))
    }

    @Test
    fun `decodes UTF-8 content spanning multiple read segments`() = runTest {
        val body = "a".repeat(8_191) + "é🙂" + "b".repeat(12_000)
        val bytes = body.encodeToByteArray()
        assertEquals(body, readBoundedResponseBody(ByteReadChannel(bytes), bytes.size.toLong(), bytes.size))
    }

    @Test
    fun `rejects a declared response length over the limit`() = runTest {
        assertFailsWith<IOException> { readBoundedResponseBody(ByteReadChannel("x".encodeToByteArray()), 6L, 5) }
    }

    @Test
    fun `rejects an unknown-length response one byte over the limit`() = runTest {
        assertFailsWith<IOException> { readBoundedResponseBody(ByteReadChannel("123456".encodeToByteArray()), null, 5) }
    }

    @Test
    fun `zero byte limit still rejects a non-empty unknown-length response`() = runTest {
        assertFailsWith<IOException> { readBoundedResponseBody(ByteReadChannel(byteArrayOf(1)), null, 0) }
    }
}
