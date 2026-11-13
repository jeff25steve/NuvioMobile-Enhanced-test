package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test
    fun queryWordsCanMatchInAnyOrderAcrossSettingMetadata() {
        val entry = entry(
            key = "media-server-api-key",
            title = "API key",
            description = "Authentication key used by the Emby media server",
            page = "Media servers",
        )

        assertEquals(listOf(entry.key), settingsSearchResults("server emby key", listOf(entry)).map { it.key })
    }

    @Test
    fun exactSettingTitleRanksAheadOfBroadDescriptionMatches() {
        val broadMatch = entry(
            key = "notification-previews",
            title = "Announce notification previews",
            description = "Configure notifications for apps",
        )
        val exactMatch = entry(
            key = "notifications",
            title = "Notifications",
            description = "Choose notification preferences",
        )

        assertEquals(
            listOf("notifications", "notification-previews"),
            settingsSearchResults("notifications", listOf(broadMatch, exactMatch)).map { it.key },
        )
    }

    @Test
    fun partialWordsAndPunctuationAreNormalized() {
        val entry = entry(
            key = "playback-subtitle-delay",
            title = "Subtitle delay",
            description = "Adjust subtitle timing",
        )

        assertEquals(listOf(entry.key), settingsSearchResults("SUB-del", listOf(entry)).map { it.key })
    }

    @Test
    fun explicitAliasesAndStableSettingKeysAreSearchable() {
        val entry = entry(
            key = "debrid-preferred-resolutions",
            title = "Preferred resolutions",
            searchTerms = listOf("Real Debrid", "Torbox", "cached streams"),
        )

        assertEquals(listOf(entry.key), settingsSearchResults("torbox resolution", listOf(entry)).map { it.key })
        assertEquals(listOf(entry.key), settingsSearchResults("debrid preferred", listOf(entry)).map { it.key })
    }

    @Test
    fun longerSearchTermsTolerateSmallTypos() {
        val entry = entry(
            key = "media-server-connections",
            title = "Connections",
            page = "Media servers",
        )

        assertEquals(listOf(entry.key), settingsSearchResults("conection", listOf(entry)).map { it.key })
    }

    @Test
    fun typoToleranceDoesNotExpandShortQueries() {
        val entry = entry(
            key = "tone",
            title = "Tone",
        )

        assertTrue(settingsSearchResults("tv", listOf(entry)).isEmpty())
    }

    @Test
    fun everyQueryTermMustStillHaveAMatch() {
        val entry = entry(
            key = "subtitle-delay",
            title = "Subtitle delay",
        )

        assertTrue(settingsSearchResults("subtitle bluetooth", listOf(entry)).isEmpty())
    }

    @Test
    fun duplicateQueryWordsDoNotChangeResultRanking() {
        val titleMatch = entry(
            key = "subtitle-title",
            title = "Subtitle",
            category = "Delay",
        )
        val descriptionMatch = entry(
            key = "delay-title",
            title = "Delay",
            description = "Subtitle",
        )
        val entries = listOf(titleMatch, descriptionMatch)

        assertEquals(
            settingsSearchResults("subtitle delay", entries).map { it.key },
            settingsSearchResults("subtitle subtitle delay", entries).map { it.key },
        )
    }

    @Test
    fun preparedSearchIndexCanBeQueriedRepeatedlyWithoutChangingRanking() {
        val exact = entry(
            key = "subtitle-delay",
            title = "Subtitle delay",
            description = "Adjust subtitle timing",
        )
        val alias = entry(
            key = "audio-delay",
            title = "Audio delay",
            searchTerms = listOf("subtitle offset"),
        )
        val index = SettingsSearchIndex(listOf(exact, alias))

        assertEquals(listOf("subtitle-delay", "audio-delay"), index.search("subtitle").map { it.key })
        assertEquals(listOf("audio-delay", "subtitle-delay"), index.search("offset delay").map { it.key })
    }

    @Test
    fun boundedEditDistanceMatchesReferenceAcrossSmallDiagonalBands() {
        val words = allBinaryWords(maxLength = 4)
        for (left in words) {
            for (right in words) {
                val exactDistance = referenceEditDistance(left, right)
                for (maximumDistance in 0..2) {
                    val expected = exactDistance.takeIf { it <= maximumDistance }
                    assertEquals(
                        expected,
                        boundedSettingsSearchEditDistance(left, right, maximumDistance),
                        "left='$left', right='$right', maximumDistance=$maximumDistance",
                    )
                }
            }
        }
    }

    @Test
    fun boundedEditDistanceRejectsCandidatesOutsideTheAllowedBand() {
        assertEquals(null, boundedSettingsSearchEditDistance("abcdefgh", "abxxefgh", 1))
        assertEquals(2, boundedSettingsSearchEditDistance("abcdefgh", "abxxefgh", 2))
    }

    private fun allBinaryWords(maxLength: Int): List<String> = buildList {
        add("")
        var level = listOf("")
        repeat(maxLength) {
            level = level.flatMap { prefix -> listOf(prefix + "a", prefix + "b") }
            addAll(level)
        }
    }

    private fun referenceEditDistance(left: String, right: String): Int {
        val distances = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in 0..left.length) distances[i][0] = i
        for (j in 0..right.length) distances[0][j] = j
        for (i in 1..left.length) {
            for (j in 1..right.length) {
                distances[i][j] = minOf(
                    distances[i - 1][j] + 1,
                    distances[i][j - 1] + 1,
                    distances[i - 1][j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1,
                )
            }
        }
        return distances[left.length][right.length]
    }

    private fun entry(
        key: String,
        title: String,
        description: String = "",
        page: String = "Playback",
        section: String = "Audio",
        category: String = "Watch",
        searchTerms: List<String> = emptyList(),
    ) = SettingsSearchEntry(
        key = key,
        title = title,
        description = description,
        page = page,
        section = section,
        category = category,
        icon = SettingsPage.Appearance.icon,
        target = SettingsSearchTarget.Page(SettingsPage.Appearance),
        searchTerms = searchTerms,
    )
}
