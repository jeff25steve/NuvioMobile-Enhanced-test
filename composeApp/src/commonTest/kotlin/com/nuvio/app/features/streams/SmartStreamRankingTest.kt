package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartStreamRankingTest {

    @Test
    fun `best recommendation obeys active instant-only filter`() {
        val uncached = stream("Torrentio", "4K uncached", "https://example.com/uncached.mkv")
        val cached = stream(
            "Torrentio",
            "1080p cached",
            "https://example.com/cached.mkv",
            StreamDebridCacheState.CACHED,
        )
        val listState = stateOf(uncached, cached)
        val allState = stateOf(uncached, cached)

        val result = compute(listState, allState, SmartStreamFilters(instantOnly = true))

        assertEquals(cached, result.best?.stream)
        assertEquals(listOf(cached), result.listState.allStreams)
    }

    @Test
    fun `best recommendation is absent when filters remove every stream`() {
        val onlyStream = stream("Torrentio", "1080p", "https://example.com/only.mkv")

        val result = compute(
            stateOf(onlyStream),
            stateOf(onlyStream),
            SmartStreamFilters(instantOnly = true),
        )

        assertNull(result.best)
    }

    @Test
    fun `best recommendation follows the selected provider filter`() {
        val selectedProvider = stream("Provider A", "1080p", "https://example.com/a.mkv")
        val otherProvider = stream(
            "Provider B",
            "4K",
            "https://example.com/b.mkv",
            StreamDebridCacheState.CACHED,
        )
        val listState = stateOf(selectedProvider, otherProvider).copy(selectedFilter = "addon:Provider A")
        val allState = stateOf(selectedProvider, otherProvider)

        val result = compute(listState, allState, SmartStreamFilters())

        assertEquals(selectedProvider, result.best?.stream)
        assertEquals(listOf(selectedProvider), result.listState.allStreams)
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
        val groups = streams.map { item ->
            AddonStreamGroup(
                addonName = item.addonName,
                addonId = item.addonId,
                streams = listOf(item),
                isLoading = false,
            )
        }.distinctBy { it.addonId }
        return StreamsUiState(
            groups = groups,
            activeAddonIds = groups.map { it.addonId }.toSet(),
        )
    }

    private fun stream(
        addonName: String,
        name: String,
        url: String,
        cacheState: StreamDebridCacheState? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        addonName = addonName,
        addonId = "addon:$addonName",
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
    )
}
