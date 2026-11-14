package com.nuvio.app.core.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkiaAnimatedImageDecodeLimiterTest {
    @Test
    fun concurrentAnimationDecodesAreBounded() = runTest {
        var active = 0
        var peak = 0
        val firstWaveReached = CompletableDeferred<Unit>()
        val releaseFirstWave = CompletableDeferred<Unit>()

        val workers = List(8) {
            async {
                withAnimatedImageDecodePermit {
                    active++
                    peak = maxOf(peak, active)
                    if (active == 2) firstWaveReached.complete(Unit)
                    releaseFirstWave.await()
                    active--
                }
            }
        }

        firstWaveReached.await()
        assertEquals(2, peak)
        releaseFirstWave.complete(Unit)
        workers.awaitAll()

        assertEquals(0, active)
        assertEquals(2, peak)
    }

    @Test
    fun decodeFailureDoesNotLeakAConcurrencyPermit() = runTest {
        assertFailsWith<IllegalStateException> {
            withAnimatedImageDecodePermit<Unit> {
                throw IllegalStateException("decode failed")
            }
        }

        var entered = false
        withAnimatedImageDecodePermit {
            entered = true
        }
        assertTrue(entered)
    }
}
