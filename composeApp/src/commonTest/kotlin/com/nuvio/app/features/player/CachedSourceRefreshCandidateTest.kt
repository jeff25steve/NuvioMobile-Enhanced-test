package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachedSourceRefreshCandidateTest {
    private val failedUrl = "https://cdn.example.com/dld/old?token=expired"

    @Test
    fun sameProviderWithNewPlayableDirectUrlIsACandidate() {
        val refreshed = stream(url = "https://cdn.example.com/dld/new?token=current")

        assertEquals(refreshed, find(listOf(refreshed)))
    }

    @Test
    fun failedUrlIsNeverReturnedAsItsOwnRefreshCandidate() {
        val unchanged = stream(url = failedUrl)

        assertNull(find(listOf(unchanged)))
    }

    @Test
    fun unresolvedOrNonPlayableMagnetStreamIsSkipped() {
        val unresolved = stream(url = null)
        val magnet = stream(url = "magnet:?xt=urn:btih:abc")

        assertNull(find(listOf(unresolved)))
        assertNull(find(listOf(magnet)))
    }

    @Test
    fun anotherAddonIsNeverACandidate() {
        val other = StreamItem(
            name = "4K",
            url = "https://other.example/new.mkv",
            addonName = "Other",
            addonId = "addon:other",
        )

        assertNull(find(listOf(other)))
    }

    @Test
    fun expectedTitleAndBingeGroupDetermineTheBestMatchingCandidate() {
        val sameProviderWrongTitle = stream(
            url = "https://cdn.example.com/other.mkv",
            name = "1080p",
            bingeGroup = "season-1",
        )
        val expectedTitleWrongGroup = stream(
            url = "https://cdn.example.com/title.mkv",
            name = "4K",
            bingeGroup = "season-2",
        )
        val expectedTitleAndGroup = stream(
            url = "https://cdn.example.com/match.mkv",
            name = "4K",
            bingeGroup = "season-1",
        )

        assertEquals(
            expectedTitleAndGroup,
            find(
                listOf(sameProviderWrongTitle, expectedTitleWrongGroup, expectedTitleAndGroup),
                expectedBingeGroup = "season-1",
            ),
        )
    }

    private fun find(
        streams: List<StreamItem>,
        expectedBingeGroup: String? = null,
    ): StreamItem? = findCredentialRefreshCandidate(
        streams = streams,
        failedUrl = failedUrl,
        expectedProviderAddonId = "addon:torrents",
        expectedProviderName = "Torrents",
        expectedStreamTitle = "4K",
        expectedBingeGroup = expectedBingeGroup,
    )

    private fun stream(
        url: String?,
        name: String = "4K",
        bingeGroup: String? = null,
    ) = StreamItem(
        name = name,
        url = url,
        addonName = "Torrents",
        addonId = "addon:torrents",
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup),
    )
}
