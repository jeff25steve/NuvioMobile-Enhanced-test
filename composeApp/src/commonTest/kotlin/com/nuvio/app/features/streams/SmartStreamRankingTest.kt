package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartStreamRankingTest {

    @Test
    fun `best recommendation obeys active resolution filter`() {
        val fourK = stream("Torrentio", "4K", "https://example.com/4k.mkv")
        val fullHd = stream("Torrentio", "1080p", "https://example.com/1080p.mkv")

        val result = compute(
            stateOf(fourK, fullHd),
            stateOf(fourK, fullHd),
            SmartStreamFilters(resolutions = setOf(SmartResolution.FHD1080)),
        )

        assertEquals(fullHd, result.best?.stream)
        assertEquals(listOf(fullHd), result.listState.allStreams)
    }

    @Test
    fun `best recommendation is absent when instant-only removes every candidate`() {
        val unresolvedTorrent = stream(
            addonName = "Torrentio",
            name = "1080p",
            url = null,
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            cacheState = StreamDebridCacheState.UNKNOWN,
        )

        val result = compute(
            stateOf(unresolvedTorrent),
            stateOf(unresolvedTorrent),
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
        )
        val allState = stateOf(selectedProvider, otherProvider).copy(selectedFilter = "addon:Provider A")
        val listState = allState

        val result = compute(listState, allState, SmartStreamFilters())

        assertEquals(selectedProvider, result.best?.stream)
        assertEquals(listOf(selectedProvider), result.listState.allStreams)
    }

    @Test
    fun `best recommendation is not narrowed by the free-text list search`() {
        val searched = stream("Provider A", "1080p", "https://example.com/a.mkv")
        val betterUnsearched = stream("Provider B", "4K", "https://example.com/b.mkv")
        val listState = stateOf(searched)
        val allState = stateOf(searched, betterUnsearched)

        val result = compute(listState, allState, SmartStreamFilters())

        assertEquals(betterUnsearched, result.best?.stream)
        assertEquals(listOf(searched), result.listState.allStreams)
    }

    private fun compute(
        listState: StreamsUiState,
        allState: StreamsUiState,
        filters: SmartStreamFilters,
    ): SmartStreamsResult {
        val settings = SmartStreamSettings(
            filters = filters,
            resolutionLimit = SmartResolutionLimit.P4K,
        )
        val device = SmartDeviceProfile(
            screen = SmartScreenInfo(
                longSidePx = 3840,
                shortSidePx = 2160,
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
        val groups = streams.groupBy { it.addonId }.map { (_, items) ->
            val first = items.first()
            AddonStreamGroup(
                addonName = first.addonName,
                addonId = first.addonId,
                streams = items,
                isLoading = false,
            )
        }
        return StreamsUiState(
            groups = groups,
            activeAddonIds = groups.map { it.addonId }.toSet(),
        )
    }

    private fun stream(
        addonName: String,
        name: String,
        url: String?,
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        infoHash = infoHash,
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
