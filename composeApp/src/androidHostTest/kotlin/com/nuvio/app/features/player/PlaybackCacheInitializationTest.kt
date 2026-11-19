package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class PlaybackCacheInitializationTest {
    @Test
    fun initializerRunsOffTheCallingThreadAndReusesItsResult() {
        val callingThread = Thread.currentThread()
        var initializationThread: Thread? = null
        var initializationCount = 0

        val initialization = BackgroundInitialization("NuvioPlaybackCacheInitTest") {
            initializationCount += 1
            initializationThread = Thread.currentThread()
            "ready"
        }

        assertEquals("ready", initialization.await())
        assertNotSame(callingThread, initializationThread)
        assertEquals("NuvioPlaybackCacheInitTest", initializationThread?.name)
        assertEquals("ready", initialization.await())
        assertEquals(1, initializationCount)
    }
}
