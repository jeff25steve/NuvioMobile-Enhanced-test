package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartStreamRankingTest {

    @Test
    fun `best recommendation obeys active instant-only filter`() {
        val uncached = stream(name = "4K uncached", url = "https://example.com/uncached.mkv")
        val cached = stream(
            name = "1080p cached",
            url = "https://example.com/cached.mkv",
            cacheState = StreamDebridCacheState.CACHED,
        )
        val listState = stateOf(uncached, cached)
        val allState = stateOf(uncached, cached)

        val result = compute(
            listState = listState,
            allState = allState,
            filters = SmartStreamFilters(instantOnly = true),
        )

        assertEquals(cached, result.best?.stream)
    }

    @Test
    fun `best recommendation is absent when filters remove every stream`() {
        val onlyStream = stream(name = "1080p", url = "https://example.com/only.mkv")

        val result = compute(
            listState = stateOf(onlyStream),
            allState = stateOf(onlyStream),
            filters = SmartStreamFilters(instantOnly = true),
        )

        assertNull(result.best)
    }

    private fun compute(
        listState: StreamsUiState,
        allState: StreamsUiState,
        filters: SmartStreamFilters,
    ): SmartStreamsResult {
        val settings = SmartStreamSettings(filters = filters)
        val device = SmartDeviceProfile(
            screen = SmartScreenInfo(
                longSidePx = 2400,
                shortSidePx = 1080,
                hdrFormats = emptySet(),
                hevcHardware = true,
                av1Hardware = false,
                decodes4k = true,
                externalDisplay = false,
                isTablet = false,
            ),
            onWifi = true,
            memoryMb = 8192,
        )
        return SmartStreamRanking.compute(
            listState = listState,
            allState = allState,
            settings = settings,
            device = device,
            debridEnabled = true,
            isEpisode = false,
        )
    }

    private fun stateOf(vararg streams: StreamItem): StreamsUiState {
        val groups = streams.mapIndexed { index, item ->
            AddonStreamGroup(
                addonName = item.addonName,
                addonId = item.addonId + index,
                streams = listOf(item),
                isLoading = false,
            )
        }
        return StreamsUiState(groups = groups)
    }

    private fun stream(
        name: String,
        url: String,
        cacheState: StreamDebridCacheState? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        addonName = "Test Addon",
        addonId = "test-addon",
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(state = state, providerId = "premiumize")
        },
    )
}
