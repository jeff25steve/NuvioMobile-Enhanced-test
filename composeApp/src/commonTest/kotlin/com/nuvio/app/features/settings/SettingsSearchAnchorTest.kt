package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSearchAnchorTest {
    @Test
    fun anchorMatchesOnlyItsExactSettingKeyOnTheExpectedPage() {
        val anchor = SettingsSearchAnchor(
            page = SettingsPage.Appearance,
            key = "amoled",
            title = "AMOLED black",
            description = "Use a pure black background",
            sectionItemIndex = 1,
        )
        assertTrue(anchor.matches(SettingsPage.Appearance, "amoled"))
        assertFalse(anchor.matches(SettingsPage.Appearance, "app-icon"))
        assertFalse(anchor.matches(SettingsPage.Advanced, "amoled"))
        assertFalse(anchor.matches(SettingsPage.Appearance, null))
    }

    @Test
    fun pageOnlySearchTargetDoesNotInventASettingAnchor() {
        val target = SettingsSearchTarget.Page(SettingsPage.Appearance)
        assertEquals(null, target.anchor)
    }

    @Test
    fun settingSearchTargetCarriesItsStableAnchor() {
        val anchor = settingsSearchAnchor(
            SettingsPage.Appearance,
            "app-language",
            "App language",
            "Choose the language used by Nuvio",
        )!!
        val target = SettingsSearchTarget.Page(SettingsPage.Appearance, anchor)
        assertEquals(SettingsPage.Appearance, target.page)
        assertEquals(anchor, target.anchor)
        assertTrue(target.anchor!!.matches(target.page, "app-language"))
    }

    @Test
    fun onlyVerifiedAppearanceRowsReceiveDeepLinkAnchors() {
        assertNull(settingsSearchAnchor(SettingsPage.Appearance, "unknown-control", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "amoled", "AMOLED", ""))
        // The existing liquid-glass catalogue key is a Layout setting, not the Appearance editor row.
        assertNull(settingsSearchAnchor(SettingsPage.Layout, "liquid-glass", "Tab bar behavior", ""))
    }

    @Test
    fun playbackPlayerSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-player-preference",
            "playback-external-player-app",
            "playback-external-player-forward-subtitles",
            "playback-external-player-send-skip-segments",
            "playback-engine",
            "playback-auto-switch-engine-on-error",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackPlayer, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackPlayer, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackPlayerAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackPlayer, "playback-engine", "Playback engine", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun playbackStreamSelectionSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-stream-reuse-last-link",
            "playback-stream-cache-duration",
            "playback-stream-selection-mode",
            "playback-stream-regex-pattern",
            "playback-stream-timeout",
            "playback-stream-source-scope",
            "playback-stream-allowed-addons",
            "playback-stream-allowed-plugins",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackStreamSelection, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackStreamSelection, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackStreamSelectionAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(
            SettingsPage.PlaybackStreamSelection,
            "playback-stream-timeout",
            "Stream Selection Timeout",
            "",
        )!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun playbackUpNextSearchResultsHaveExactRowAnchors() {
        val keys = listOf("playback-up-next-auto-play", "playback-up-next-fallback", "playback-up-next-prefer-binge", "playback-up-next-reuse-binge", "playback-up-next-preload", "playback-up-next-threshold-mode")
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackUpNext, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackUpNext, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackUpNextAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackUpNext, "playback-up-next-threshold-mode", "Threshold Mode", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun unknownPlaybackRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackPlayer, "playback-unregistered-row", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackStreamSelection, "playback-unregistered-row", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackUpNext, "playback-unregistered-row", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackVideo, "playback-engine", "Playback engine", ""))
    }

    @Test
    fun anchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.Appearance, "amoled", "AMOLED", "")!!
        assertEquals(2, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(2, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(2, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, false))
    }
    @Test
    fun playbackSkipSegmentsSearchResultsHaveExactRowAnchors() {
        val keys = listOf("playback-skip-intro-enabled", "playback-skip-intro-offset", "playback-auto-skip-segments", "playback-anime-skip-enabled", "playback-anime-skip-client-id", "playback-intro-submit-enabled", "playback-introdb-api-key")
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackSkipSegments, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackSkipSegments, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackSkipSegmentsAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackSkipSegments, "playback-auto-skip-segments", "Auto Skip Segments", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

}
