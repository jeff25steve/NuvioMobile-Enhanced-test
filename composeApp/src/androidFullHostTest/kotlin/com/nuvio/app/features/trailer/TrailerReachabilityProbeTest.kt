package com.nuvio.app.features.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailerReachabilityProbeTest {

    @Test
    fun `probe returns after status without consuming the delayed body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("unused trailer response".repeat(128))
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            val client = OkHttpClient()
            val call = client.newCall(
                Request.Builder()
                    .url(server.url("/probe"))
                    .header("Range", "bytes=0-0")
                    .build(),
            )

            val reachable = withTimeout(2_000) {
                awaitTrailerReachabilityResponse(call)
            }

            assertTrue(reachable)
            assertTrue(call.isExecuted())
            server.takeRequest(5, TimeUnit.SECONDS)
            Unit
        }
    }

    @Test
    fun `cancelling the probe cancels the pending okhttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeadersDelay(5, TimeUnit.SECONDS)
                    .setBody("ok"),
            )
            val client = OkHttpClient()
            val call = client.newCall(
                Request.Builder()
                    .url(server.url("/probe"))
                    .header("Range", "bytes=0-0")
                    .build(),
            )
            val probe = async(Dispatchers.Default) {
                awaitTrailerReachabilityResponse(call)
            }

            withContext(Dispatchers.IO) {
                server.takeRequest(5, TimeUnit.SECONDS)
            }
            probe.cancelAndJoin()

            assertTrue(call.isCanceled())
        }
    }

    @Test
    fun `non successful http responses are not reachable`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val client = OkHttpClient()
            val call = client.newCall(
                Request.Builder()
                    .url(server.url("/probe"))
                    .header("Range", "bytes=0-0")
                    .build(),
            )

            assertFalse(awaitTrailerReachabilityResponse(call))
        }
    }
}
