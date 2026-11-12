package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSearchAnchorTest {
    @Test
    fun notificationsSearchResultHasExactRowAnchor() {
        val anchor = settingsSearchAnchor(
            SettingsPage.Notifications,
            "episode-release-alerts",
            "Episode release alerts",
            "",
        )
        assertEquals("episode-release-alerts", anchor?.key)
        assertEquals(SettingsPage.Notifications, anchor?.page)
        assertEquals(0, anchor?.sectionItemIndex)
    }

    @Test
    fun unknownNotificationRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.Notifications, "notification-test", "Send test notification", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Notifications, "unknown-notification", "Unknown", ""))
    }

    @Test
    fun downloadsSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "downloads-allow-mobile-data",
            "downloads-show-download-button",
            "download-location",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.Downloads, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.Downloads, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun downloadsAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(
            SettingsPage.Downloads,
            "downloads-allow-mobile-data",
            "Allow mobile data",
            "",
        )!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun unknownDownloadsRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.Downloads, "downloads-private-value", "Private value", ""))
    }

    @Test
    fun playbackBufferSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-exo-native-memory",
            "playback-custom-buffers",
            "playback-min-buffer",
            "playback-max-buffer",
            "playback-start-buffer",
            "playback-buffer-after-stall",
            "playback-back-buffer",
            "playback-target-buffer-size",
            "playback-vod-cache",
            "playback-vod-cache-auto-size",
            "playback-vod-cache-size",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackBuffer, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackBuffer, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackBufferAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackBuffer, "playback-target-buffer-size", "Target buffer size", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun playbackP2pSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-p2p-enabled",
            "playback-p2p-hide-stats",
            "playback-p2p-profile",
            "playback-p2p-cache-size",
            "playback-p2p-clear-cache",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackP2p, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackP2p, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackP2pAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackP2p, "playback-p2p-cache-size", "P2P cache size", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun unknownBufferAndP2pRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackBuffer, "playback-buffer-unknown", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackP2p, "playback-p2p-secret", "Secret", ""))
    }

    @Test
    fun playbackVideoSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-libmpv-video-output",
            "playback-libmpv-hardware-decoding",
            "playback-libmpv-yuv420p",
            "playback-decoder-priority",
            "playback-dv7-hevc",
            "playback-tunneled-playback",
            "playback-audio-passthrough",
            "playback-anime-upscale",
            "playback-anime-line-sharpen",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackVideo, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackVideo, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackVideoAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(
            SettingsPage.PlaybackVideo,
            "playback-libmpv-video-output",
            "Libmpv video output",
            "",
        )!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun unknownPlaybackVideoRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackVideo, "playback-video-unknown", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackVideo, "playback-ios-hardware-decoder", "iOS hardware decoder", ""))
    }

    @Test
    fun playbackSubtitleSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-preferred-subtitles",
            "playback-secondary-subtitles",
            "playback-subtitle-font",
            "playback-subtitle-shadow",
            "playback-libass",
            "playback-libass-render-mode",
            "playback-libass-render",
            "playback-libmpv-ass",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackSubtitles, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackSubtitles, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackSubtitleAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(
            SettingsPage.PlaybackSubtitles,
            "playback-subtitle-font",
            "Subtitle font",
            "",
        )!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun unknownPlaybackSubtitleRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackSubtitles, "playback-subtitle-unknown", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackSubtitles, "playback-subtitle-background-opacity", "Opacity", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackSubtitles, "playback-subtitle-background-roundness", "Roundness", ""))
    }

    @Test
    fun duplicateCatalogueRowsCollapseAndPreferExactRowNavigation() {
        val page = SettingsPage.PlaybackAudio
        val anchor = settingsSearchAnchor(page, "playback-audio-downmix", "Downmix", "")!!
        val pageOnly = SettingsSearchEntry(
            key = "legacy-downmix-entry",
            title = "Downmix",
            description = "Convert multichannel audio",
            page = "Playback",
            section = "Audio",
            category = "General",
            icon = androidx.compose.material.icons.Icons.Rounded.Search,
            target = SettingsSearchTarget.Page(page),
            searchTerms = listOf("surround sound"),
        )
        val anchored = pageOnly.copy(
            key = "playback-audio-downmix",
            target = SettingsSearchTarget.Page(page, anchor),
            searchTerms = listOf("channel mix"),
        )

        val results = deduplicateSettingsSearchEntries(listOf(pageOnly, anchored))

        assertEquals(1, results.size)
        assertEquals("playback-audio-downmix", (results.single().target as SettingsSearchTarget.Page).anchor?.key)
        assertEquals(listOf("surround sound", "channel mix"), results.single().searchTerms)
    }

    @Test
    fun duplicateLabelsInDifferentSectionsOrDestinationsRemainDistinct() {
        fun entry(page: SettingsPage, section: String) = SettingsSearchEntry(
            key = "${page.name}-$section",
            title = "Enabled",
            description = "Turn this feature on",
            page = "Settings",
            section = section,
            category = "General",
            icon = androidx.compose.material.icons.Icons.Rounded.Search,
            target = SettingsSearchTarget.Page(page),
        )

        val results = deduplicateSettingsSearchEntries(
            listOf(
                entry(SettingsPage.PlaybackAudio, "Audio"),
                entry(SettingsPage.PlaybackAudio, "Subtitles"),
                entry(SettingsPage.Notifications, "Audio"),
            ),
        )

        assertEquals(3, results.size)
    }

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

    @Test
    fun playbackInterfaceSearchResultsHaveExactRowAnchors() {
        val keys = listOf("playback-interface-legacy-layout", "playback-interface-title-logo", "playback-interface-control-glass", "playback-interface-skip-buttons", "playback-interface-toolbar-layout", "playback-interface-legacy-top-buttons", "playback-interface-legacy-bottom-buttons", "playback-interface-loading-overlay", "playback-interface-exo-chapters", "playback-interface-loading-status", "playback-interface-pause-overlay", "playback-interface-parental-guide", "playback-interface-movie-recommendations", "playback-interface-mini-player", "playback-interface-seek-preview", "playback-interface-touch-gestures", "playback-interface-swipe-to-seek", "playback-interface-hold-to-speed", "playback-interface-hold-speed-value", "playback-interface-skip-backward", "playback-interface-skip-forward")
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackInterface, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackInterface, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackInterfaceAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackInterface, "playback-interface-toolbar-layout", "Toolbar Layout", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }


    @Test
    fun playbackAudioSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "playback-audio-preferred-language",
            "playback-audio-secondary-language",
            "playback-audio-night-mode-button",
            "playback-audio-remember-delay",
            "playback-audio-downmix",
            "playback-audio-downmix-channels",
            "playback-audio-downmix-maintain-volume",
            "playback-audio-ios-output",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.PlaybackAudio, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.PlaybackAudio, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun playbackAudioAnchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.PlaybackAudio, "playback-audio-downmix-channels", "Number of Channels", "")!!
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(0, settingsSearchAnchorListIndex(anchor, true, false))
    }

    @Test
    fun advancedSettingsSearchResultsHaveExactRowAnchors() {
        val keys = listOf(
            "advanced-remember-last-profile",
            "advanced-dns-provider",
            "advanced-sentry-reports",
        )
        keys.forEach { key ->
            val anchor = settingsSearchAnchor(SettingsPage.Advanced, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.Advanced, anchor?.page)
            assertEquals(0, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun advancedSettingsUnknownRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "advanced-clear-cache", "Clear cache", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "advanced-debug-logs", "Debug logs", ""))
    }

}
