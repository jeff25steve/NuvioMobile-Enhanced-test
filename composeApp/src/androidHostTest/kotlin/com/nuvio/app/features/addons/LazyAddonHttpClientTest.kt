package com.nuvio.app.features.addons

import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LazyAddonHttpClientTest {
    @Test
    fun clientIsCreatedOnlyWhenRequestedAndThenReused() {
        val creations = AtomicInteger()
        val provider = LazyAddonHttpClient {
            creations.incrementAndGet()
            OkHttpClient.Builder().build()
        }

        assertEquals(0, creations.get())
        val first = provider.get()

        assertEquals(1, creations.get())
        assertSame(first, provider.get())
        assertEquals(1, creations.get())
    }

    @Test
    fun concurrentFirstRequestsShareOneClientInstance() {
        val workerCount = 8
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val creations = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<OkHttpClient>())
        val provider = LazyAddonHttpClient {
            creations.incrementAndGet()
            OkHttpClient.Builder().build()
        }
        val workers = List(workerCount) {
            Thread {
                ready.countDown()
                if (start.await(5, TimeUnit.SECONDS)) {
                    results += provider.get()
                }
            }.apply { start() }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000) }

        assertEquals(1, creations.get())
        assertEquals(workerCount, results.size)
        results.forEach { assertSame(results.first(), it) }
    }
}
