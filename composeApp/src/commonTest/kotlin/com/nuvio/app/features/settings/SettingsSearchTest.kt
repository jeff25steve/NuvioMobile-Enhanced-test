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
