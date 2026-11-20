package com.nuvio.app.features.details

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MetaDetailsEmbeddedStreamsTest {
    @Test
    fun `root lookup flattens episode streams in video order`() {
        val first = stream("first")
        val second = stream("second")
        val third = stream("third")
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "show:1:1", title = "Episode 1", streams = listOf(first, second)),
                MetaVideo(id = "show:1:2", title = "Episode 2", streams = listOf(third)),
            ),
        )

        assertEquals(listOf(first, second, third), findEmbeddedStreamsForMeta(meta, "show"))
    }

    @Test
    fun `exact episode lookup keeps its direct stream list`() {
        val first = stream("first")
        val second = stream("second")
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "show:1:1", title = "Episode 1", streams = listOf(first)),
                MetaVideo(id = "show:1:2", title = "Episode 2", streams = listOf(second)),
            ),
        )

        assertSame(meta.videos[1].streams, findEmbeddedStreamsForMeta(meta, "show:1:2"))
    }

    private fun stream(name: String) = StreamItem(name = name, addonName = "addon", addonId = "addon")
}
