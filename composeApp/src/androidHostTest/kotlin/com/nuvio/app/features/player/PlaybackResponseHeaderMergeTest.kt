package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaybackResponseHeaderMergeTest {
    @Test
    fun overrideReplacesUpstreamHeaderRegardlessOfNameCase() {
        val upstream = linkedMapOf(
            "content-type" to listOf("application/octet-stream"),
            "ETag" to listOf("abc"),
        )

        val merged = mergePlaybackResponseHeaders(
            upstream,
            mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
        )

        assertEquals("application/vnd.apple.mpegurl", merged["Content-Type"]?.single())
        assertEquals(null, merged["content-type"])
        assertEquals(listOf("abc"), merged["ETag"])
    }

    @Test
    fun emptyOverridesReturnOriginalHeadersWithoutCopying() {
        val upstream = mapOf("Content-Type" to listOf("video/mp4"))

        assertSame(upstream, mergePlaybackResponseHeaders(upstream, emptyMap()))
    }

    @Test
    fun multipleCaseVariantsAreRemovedBeforeOverrideIsAdded() {
        val upstream = linkedMapOf(
            "content-type" to listOf("application/octet-stream"),
            "Content-Type" to listOf("video/mp4"),
        )

        val merged = mergePlaybackResponseHeaders(upstream, mapOf("CONTENT-TYPE" to "application/dash+xml"))

        assertEquals(1, merged.keys.count { it.equals("content-type", ignoreCase = true) })
        assertEquals("application/dash+xml", merged["CONTENT-TYPE"]?.single())
    }
}
