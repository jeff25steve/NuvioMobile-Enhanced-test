package com.nuvio.app.features.settings

import com.nuvio.app.core.network.DnsSettingsRepository

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
        // Unknown keys stay page-only; the known Layout row is anchored to its rendered section.
        assertNull(settingsSearchAnchor(SettingsPage.Layout, "unknown-layout-row", "Unknown", ""))
    }

    @Test
    fun layoutSearchResultsHaveExactRenderedRowAnchors() {
        val expected = mapOf(
            "dynamic-artwork-background" to 0,
            "catalog-accent" to 0,
            "liquid-glass" to 3,
            "navigation-bar" to 3,
        )
        expected.forEach { (key, sectionItemIndex) ->
            val anchor = settingsSearchAnchor(SettingsPage.Layout, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.Layout, anchor?.page)
            assertEquals(sectionItemIndex, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun layoutAnchorIndexesAccountForPhoneAndTabletHeaders() {
        val homeAnchor = settingsSearchAnchor(
            SettingsPage.Layout,
            "dynamic-artwork-background",
            "Dynamic artwork background",
            "",
        )!!
        val navigationAnchor = settingsSearchAnchor(
            SettingsPage.Layout,
            "navigation-bar",
            "Navigation bar style",
            "",
        )!!
        assertEquals(1, settingsSearchAnchorListIndex(homeAnchor, false, true))
        assertEquals(0, settingsSearchAnchorListIndex(homeAnchor, true, false))
        assertEquals(4, settingsSearchAnchorListIndex(navigationAnchor, false, true))
        assertEquals(3, settingsSearchAnchorListIndex(navigationAnchor, true, false))
    }

    @Test
    fun remainingSettingsPagesExposeExactRenderedSearchAnchors() {
        val expected = listOf(
            Triple(SettingsPage.Account, "account-status", 0),
            Triple(SettingsPage.Account, "account-sign-out", 0),
            Triple(SettingsPage.Account, "account-delete", 0),
            Triple(SettingsPage.ContentDiscovery, "recent-searches", 0),
            Triple(SettingsPage.Streams, "stream-addon-logo", 0),
            Triple(SettingsPage.Streams, "stream-background", 0),
            Triple(SettingsPage.Streams, "stream-size-badges", 0),
            Triple(SettingsPage.Streams, "stream-badge-position", 0),
            Triple(SettingsPage.Streams, "stream-badge-urls", 0),
            Triple(SettingsPage.MetaScreen, "meta-poster-transition", 0),
            Triple(SettingsPage.MetaScreen, "meta-overview", 1),
            Triple(SettingsPage.MetaScreen, "meta-actions", 1),
            Triple(SettingsPage.MetaScreen, "meta-details", 1),
            Triple(SettingsPage.MetaScreen, "meta-trailers", 1),
            Triple(SettingsPage.MetaScreen, "meta-cast", 1),
            Triple(SettingsPage.MetaScreen, "meta-episodes", 1),
            Triple(SettingsPage.MetaScreen, "meta-production", 1),
            Triple(SettingsPage.MetaScreen, "meta-more-like-this", 1),
            Triple(SettingsPage.MetaScreen, "meta-collection", 1),
            Triple(SettingsPage.MetaScreen, "meta-comments", 1),
            Triple(SettingsPage.MetaScreen, "meta-background-mode", 0),
            Triple(SettingsPage.MetaScreen, "meta-tabs", 0),
            Triple(SettingsPage.MetaScreen, "meta-overall-ratings", 0),
            Triple(SettingsPage.MetaScreen, "meta-episode-ratings", 0),
            Triple(SettingsPage.MetaScreen, "meta-episode-cards", 0),
            Triple(SettingsPage.MetaScreen, "meta-shuffle", 0),
            Triple(SettingsPage.MetaScreen, "meta-blur-episodes", 0),
            Triple(SettingsPage.Notifications, "notification-test", 1),
            Triple(SettingsPage.TmdbEnrichment, "tmdb-enable", 0),
            Triple(SettingsPage.TmdbEnrichment, "tmdb-api-key", 0),
            Triple(SettingsPage.TmdbEnrichment, "tmdb-language", 1),
            Triple(SettingsPage.TmdbEnrichment, "tmdb-trailers", 2),
            Triple(SettingsPage.TmdbEnrichment, "tmdb-collections", 2),
            Triple(SettingsPage.MdbListRatings, "mdb-enable", 0),
            Triple(SettingsPage.MdbListRatings, "mdb-show-on-hero", 0),
            Triple(SettingsPage.MdbListRatings, "mdb-api-key", 1),
            Triple(SettingsPage.MdbListRatings, "mdb-imdb", 2),
            Triple(SettingsPage.MdbListRatings, "mdb-audience", 2),
            Triple(SettingsPage.Omdb, "omdb-api-key", 0),
            Triple(SettingsPage.PosterCustomization, "poster-width", 0),
            Triple(SettingsPage.PosterCustomization, "poster-radius", 0),
            Triple(SettingsPage.PosterCustomization, "poster-landscape", 0),
            Triple(SettingsPage.PosterCustomization, "poster-hide-labels", 0),
            Triple(SettingsPage.PosterCustomization, "card-depth-effect", 2),
            Triple(SettingsPage.Homescreen, "home-hero", 1),
            Triple(SettingsPage.Homescreen, "home-catalog-type", 1),
            Triple(SettingsPage.Homescreen, "home-hide-unreleased", 1),
            Triple(SettingsPage.DebugLogs, "debug-copy-logs", 0),
            Triple(SettingsPage.DebugLogs, "debug-clear-logs", 0),
            Triple(SettingsPage.DebugLogs, "debug-log-viewer", 0),
            Triple(SettingsPage.DiscordRpc, "discord-login", 0),
            Triple(SettingsPage.DiscordRpc, "discord-enable", 1),
            Triple(SettingsPage.DiscordRpc, "discord-activity-type", 1),
            Triple(SettingsPage.DiscordRpc, "discord-status", 1),
            Triple(SettingsPage.DiscordRpc, "discord-show-title-as-name", 2),
            Triple(SettingsPage.DiscordRpc, "discord-show-poster", 2),
            Triple(SettingsPage.DiscordRpc, "discord-show-playback-state", 2),
            Triple(SettingsPage.DiscordRpc, "discord-show-timestamps", 2),
            Triple(SettingsPage.DiscordRpc, "discord-hide-on-pause", 2),
            Triple(SettingsPage.DiscordRpc, "discord-show-imdb-button", 2),
            Triple(SettingsPage.DiscordRpc, "discord-application-id", 3),
            Triple(SettingsPage.TraktAuthentication, "trakt-authentication", 0),
            Triple(SettingsPage.TraktAuthentication, "simkl-authentication", 0),
            Triple(SettingsPage.TraktAuthentication, "mdblist-authentication", 0),
            Triple(SettingsPage.TraktAuthentication, "private-viewing", 1),
            Triple(SettingsPage.TraktAuthentication, "trakt-library-source", 2),
            Triple(SettingsPage.TraktAuthentication, "trakt-watch-progress", 2),
        )
        expected.forEach { (page, key, sectionItemIndex) ->
            val anchor = settingsSearchAnchor(page, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(page, anchor?.page)
            assertEquals(sectionItemIndex, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun debridSearchAnchorsMatchVisibleSectionsAndProviderRows() {
        val alwaysVisible = listOf(
            "debrid-cloud-library" to 0,
            "debrid-link-resolving" to 0,
            "debrid-provider-torbox" to 1,
            "debrid-provider-premiumize" to 1,
        )
        alwaysVisible.forEach { (key, expectedIndex) ->
            assertEquals(expectedIndex, settingsSearchAnchor(SettingsPage.Debrid, key, key, "")?.sectionItemIndex)
        }
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-provider-realdebrid", "Real-Debrid", ""))
    }

    @Test
    fun debridConditionalSearchAnchorsRequireTheirControlsToBeRendered() {
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-resolve-provider", "Resolve with", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-instant-playback", "Instant playback", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-max-results", "Maximum results", "", debridCanResolvePlayableLinks = false))
        assertEquals(0, settingsSearchAnchor(SettingsPage.Debrid, "debrid-resolve-provider", "Resolve with", "", debridCanResolvePlayableLinks = true, debridCanChooseResolverProvider = true)?.sectionItemIndex)
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-resolve-provider", "Resolve with", "", debridCanResolvePlayableLinks = true, debridCanChooseResolverProvider = false))
        assertEquals(2, settingsSearchAnchor(SettingsPage.Debrid, "debrid-instant-playback", "Instant playback", "", debridCanResolvePlayableLinks = true)?.sectionItemIndex)
        assertNull(settingsSearchAnchor(SettingsPage.Debrid, "debrid-instant-playback-count", "Prepare count", "", debridCanResolvePlayableLinks = true, debridInstantPlaybackEnabled = false))
        assertEquals(2, settingsSearchAnchor(SettingsPage.Debrid, "debrid-instant-playback-count", "Prepare count", "", debridCanResolvePlayableLinks = true, debridInstantPlaybackEnabled = true)?.sectionItemIndex)
        assertEquals(3, settingsSearchAnchor(SettingsPage.Debrid, "debrid-max-results", "Maximum results", "", debridCanResolvePlayableLinks = true)?.sectionItemIndex)
        assertEquals(3, settingsSearchAnchor(SettingsPage.Debrid, "debrid-preferred-languages", "Preferred languages", "", debridCanResolvePlayableLinks = true)?.sectionItemIndex)
        assertEquals(4, settingsSearchAnchor(SettingsPage.Debrid, "debrid-name-template", "Name template", "", debridCanResolvePlayableLinks = true)?.sectionItemIndex)
    }

    @Test
    fun liveTvSearchAnchorsFollowPlaylistDependentSectionIndexes() {
        assertNull(settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-navigation", "Navigation", ""))
        assertEquals(0, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-xtream-codes", "Xtream", "", liveTvHasPlaylist = false)?.sectionItemIndex)
        assertEquals(1, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-playlist-url", "Playlist URL", "", liveTvHasPlaylist = false)?.sectionItemIndex)
        assertEquals(2, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-epg-auto", "Automatic EPG", "", liveTvHasPlaylist = false)?.sectionItemIndex)
        assertEquals(0, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-navigation", "Navigation", "", liveTvHasPlaylist = true)?.sectionItemIndex)
        assertEquals(1, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-reload-channels", "Reload channels", "", liveTvHasPlaylist = true)?.sectionItemIndex)
        assertEquals(2, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-xtream-codes", "Xtream", "", liveTvHasPlaylist = true)?.sectionItemIndex)
        assertEquals(3, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-playlist-url", "Playlist URL", "", liveTvHasPlaylist = true)?.sectionItemIndex)
        assertEquals(4, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-epg-auto", "Automatic EPG", "", liveTvHasPlaylist = true)?.sectionItemIndex)
    }

    @Test
    fun allDebridFilterSearchRowsResolveToTheResultManagementSection() {
        val keys = listOf(
            "debrid-preferred-resolutions",
            "debrid-required-resolutions",
            "debrid-excluded-resolutions",
            "debrid-preferred-qualities",
            "debrid-required-qualities",
            "debrid-excluded-qualities",
            "debrid-preferred-visual-tags",
            "debrid-required-visual-tags",
            "debrid-excluded-visual-tags",
            "debrid-preferred-audio-tags",
            "debrid-required-audio-tags",
            "debrid-excluded-audio-tags",
            "debrid-preferred-channels",
            "debrid-required-channels",
            "debrid-excluded-channels",
            "debrid-preferred-encodes",
            "debrid-required-encodes",
            "debrid-excluded-encodes",
            "debrid-preferred-languages",
            "debrid-required-languages",
            "debrid-excluded-languages",
            "debrid-required-release-groups",
            "debrid-excluded-release-groups",
        )
        keys.forEach { key ->
            assertEquals(
                3,
                settingsSearchAnchor(
                    SettingsPage.Debrid,
                    key,
                    key,
                    "",
                    debridCanResolvePlayableLinks = true,
                )?.sectionItemIndex,
            )
        }
    }

    @Test
    fun allLiveTvSearchRowsHaveRenderedTargetsForBothPlaylistStates() {
        assertNull(settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-navigation", "Navigation", "", liveTvHasPlaylist = false))
        assertEquals(0, settingsSearchAnchor(SettingsPage.LiveTv, "live-tv-navigation", "Navigation", "", liveTvHasPlaylist = true)?.sectionItemIndex)
        val rows = listOf(
            Triple("live-tv-xtream-codes", 0, 2),
            Triple("live-tv-stalker-portal", 0, 2),
            Triple("live-tv-stalker-mac", 0, 2),
            Triple("live-tv-playlist", 1, 3),
            Triple("live-tv-playlist-url", 1, 3),
            Triple("live-tv-playlist-name", 1, 3),
            Triple("live-tv-local-playlist", 1, 3),
            Triple("live-tv-epg-auto", 2, 4),
            Triple("live-tv-epg-url", 2, 4),
            Triple("live-tv-epg-add", 2, 4),
            Triple("live-tv-epg-refresh", 2, 4),
        )
        rows.forEach { (key, withoutPlaylistIndex, withPlaylistIndex) ->
            assertEquals(
                withoutPlaylistIndex,
                settingsSearchAnchor(SettingsPage.LiveTv, key, key, "", liveTvHasPlaylist = false)?.sectionItemIndex,
            )
            assertEquals(
                withPlaylistIndex,
                settingsSearchAnchor(SettingsPage.LiveTv, key, key, "", liveTvHasPlaylist = true)?.sectionItemIndex,
            )
        }
    }

    @Test
    fun remainingSettingsPagesRejectUnknownSearchAnchorKeys() {
        val pages = listOf(
            SettingsPage.Account,
            SettingsPage.ContentDiscovery,
            SettingsPage.Streams,
            SettingsPage.MetaScreen,
            SettingsPage.Notifications,
            SettingsPage.TraktAuthentication,
            SettingsPage.TmdbEnrichment,
            SettingsPage.MdbListRatings,
            SettingsPage.Omdb,
            SettingsPage.DiscordRpc,
            SettingsPage.DebugLogs,
            SettingsPage.Homescreen,
            SettingsPage.PosterCustomization,
            SettingsPage.Debrid,
            SettingsPage.LiveTv,
        )
        pages.forEach { page ->
            assertNull(settingsSearchAnchor(page, "not-a-rendered-setting", "Unknown", ""))
        }
        assertNull(settingsSearchAnchor(SettingsPage.PosterCustomization, "poster-card-depth-edge", "Edge", ""))
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
        val expectedAnchors = buildList {
            add("advanced-remember-last-profile" to 0)
            if (DnsSettingsRepository.isSupported) add("advanced-dns-provider" to 1)
            if (SentrySettingsRepository.isSupported) {
                add("advanced-sentry-reports" to (1 + if (DnsSettingsRepository.isSupported) 1 else 0))
            }
        }
        expectedAnchors.forEach { (key, expectedIndex) ->
            val anchor = settingsSearchAnchor(SettingsPage.Advanced, key, key, "")
            assertEquals(key, anchor?.key)
            assertEquals(SettingsPage.Advanced, anchor?.page)
            assertEquals(expectedIndex, anchor?.sectionItemIndex)
        }
    }

    @Test
    fun advancedSettingsUnknownRowsRemainPageOnly() {
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "clear-cw-cache", "Clear continue watching cache", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "clear-metadata-cache", "Clear metadata cache", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "advanced-debug-logs", "Debug logs", ""))
    }

    @Test
    fun settingsSearchFindsHomeDetailsTrackingAndIntegrationAliases() {
        fun entry(
            key: String,
            page: SettingsPage,
            title: String,
            searchTerms: List<String>,
        ) = SettingsSearchEntry(
            key = key,
            title = title,
            description = "",
            page = "Settings",
            section = "",
            category = "General",
            icon = androidx.compose.material.icons.Icons.Rounded.Search,
            target = SettingsSearchTarget.Page(page),
            searchTerms = searchTerms,
        )

        val entries = listOf(
            entry("home-layout", SettingsPage.Homescreen, "Homescreen", listOf("homepage", "hero trailers")),
            entry("detail-page", SettingsPage.MetaScreen, "Details", listOf("movie details", "metadata", "synopsis")),
            entry("tracking", SettingsPage.TraktAuthentication, "Tracking", listOf("watch history", "scrobbling", "trakt")),
            entry("integrations", SettingsPage.Integrations, "Integrations", listOf("link accounts", "third party")),
        )

        assertEquals(
            SettingsPage.Homescreen,
            (settingsSearchResults("homepage", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.MetaScreen,
            (settingsSearchResults("movie details", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.TraktAuthentication,
            (settingsSearchResults("watch history", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Integrations,
            (settingsSearchResults("link accounts", entries).first().target as SettingsSearchTarget.Page).page,
        )
    }

    @Test
    fun settingsSearchFindsAccountAndLayoutTaskAliases() {
        fun entry(key: String, page: SettingsPage, title: String, terms: List<String>) =
            SettingsSearchEntry(
                key = key,
                title = title,
                description = "",
                page = "Settings",
                section = "",
                category = "General",
                icon = androidx.compose.material.icons.Icons.Rounded.Search,
                target = SettingsSearchTarget.Page(page),
                searchTerms = terms,
            )

        val entries = listOf(
            entry("account", SettingsPage.Account, "Account", listOf("manage account", "log in", "log out", "user profile", "profile management", "account access", "sign in", "sign out", "account details")),
            entry("layout", SettingsPage.Layout, "Layout", listOf("navigation tabs", "bottom navigation", "catalog layout", "tab navigation", "bottom bar", "navigation bar", "sidebar navigation", "grid view", "list view")),
            entry("appearance", SettingsPage.Appearance, "Appearance", listOf("color scheme", "app appearance", "display theme", "localization", "translation", "dark mode", "light mode")),
        )

        assertEquals(
            SettingsPage.Account,
            (settingsSearchResults("manage account", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Account,
            (settingsSearchResults("log out", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Layout,
            (settingsSearchResults("bottom navigation", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Layout,
            (settingsSearchResults("catalog layout", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Layout,
            (settingsSearchResults("sidebar navigation", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Appearance,
            (settingsSearchResults("color scheme", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Appearance,
            (settingsSearchResults("localization", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.Account,
            (settingsSearchResults("profile management", entries).first().target as SettingsSearchTarget.Page).page,
        )
    }

    @Test
    fun settingsSearchFindsContinueWatchingAndPosterStyleAliases() {
        fun entry(key: String, page: SettingsPage, title: String, terms: List<String>) =
            SettingsSearchEntry(
                key = key,
                title = title,
                description = "",
                page = "Settings",
                section = "",
                category = "General",
                icon = androidx.compose.material.icons.Icons.Rounded.Search,
                target = SettingsSearchTarget.Page(page),
                searchTerms = terms,
            )

        val entries = listOf(
            entry("continue-watching", SettingsPage.ContinueWatching, "Continue Watching", listOf("resume playback", "keep watching", "resume prompt")),
            entry("poster-card-style", SettingsPage.PosterCustomization, "Poster Style", listOf("poster design", "card appearance", "movie artwork cards")),
        )

        assertEquals(
            SettingsPage.ContinueWatching,
            (settingsSearchResults("resume playback", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.ContinueWatching,
            (settingsSearchResults("keep watching", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.PosterCustomization,
            (settingsSearchResults("poster design", entries).first().target as SettingsSearchTarget.Page).page,
        )
        assertEquals(
            SettingsPage.PosterCustomization,
            (settingsSearchResults("card appearance", entries).first().target as SettingsSearchTarget.Page).page,
        )
    }

    @Test
    fun continueWatchingSearchRowsHaveExactRenderedAnchors() {
        val rows = listOf(
            "continue-watching-show-continue-watching" to 0,
            "continue-watching-style" to 1,
            "continue-watching-episode-thumbnails" to 2,
            "continue-watching-up-next" to 2,
            "continue-watching-unaired-next-up" to 2,
            "continue-watching-blur-next-up" to 2,
            "continue-watching-resume-prompt" to 3,
            "continue-watching-sort-mode" to 4,
        )
        rows.forEach { (key, expectedIndex) ->
            assertEquals(
                expectedIndex,
                settingsSearchAnchor(
                    SettingsPage.ContinueWatching,
                    key,
                    key,
                    "",
                    continueWatchingPosterStyle = false,
                    continueWatchingEpisodeThumbnails = true,
                )?.sectionItemIndex,
                key,
            )
        }
    }

    @Test
    fun continueWatchingConditionalRowsRespectVisibleConfiguration() {
        assertNull(
            settingsSearchAnchor(
                SettingsPage.ContinueWatching,
                "continue-watching-episode-thumbnails",
                "Episode thumbnails",
                "",
                continueWatchingPosterStyle = true,
            ),
        )
        assertNull(
            settingsSearchAnchor(
                SettingsPage.ContinueWatching,
                "continue-watching-blur-next-up",
                "Blur next up",
                "",
                continueWatchingPosterStyle = false,
                continueWatchingEpisodeThumbnails = false,
            ),
        )
    }

    @Test
    fun posterCustomizationSearchKeysMatchRenderedAnchors() {
        val keys = listOf(
            "poster-width",
            "poster-radius",
            "poster-landscape",
            "poster-hide-labels",
            "poster-always-show-landscape-clearlogo",
            "card-depth-effect",
            "card-depth-edge",
            "card-depth-sheen",
            "card-depth-edge-coverage",
        )
        keys.forEach { key ->
            assertEquals(
                key,
                settingsSearchAnchor(SettingsPage.PosterCustomization, key, key, "")?.key,
                key,
            )
        }
        assertNull(settingsSearchAnchor(SettingsPage.PosterCustomization, "poster-poster-width", "Width", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PosterCustomization, "poster-card-depth-edge", "Edge", ""))
    }

    @Test
    fun trackingFeatureSearchRowsHaveExactAnchors() {
        val rows = listOf(
            "trakt-more-like-this-source" to 2,
            "trakt-continue-watching-window" to 4,
            "trakt-comments" to 4,
        )
        rows.forEach { (key, expectedIndex) ->
            assertEquals(
                expectedIndex,
                settingsSearchAnchor(SettingsPage.TraktAuthentication, key, key, "")?.sectionItemIndex,
                key,
            )
        }
    }

    @Test
    fun homescreenSectionSearchResultsHaveExactSectionAnchors() {
        val rows = listOf(
            "home-hero" to 1,
            "home-catalog-type" to 1,
            "home-hide-unreleased" to 1,
            "home-hero-sources" to 2,
            "home-catalogs" to 3,
        )
        rows.forEach { (key, expectedIndex) ->
            assertEquals(
                expectedIndex,
                settingsSearchAnchor(SettingsPage.Homescreen, key, key, "")?.sectionItemIndex,
                key,
            )
        }
    }

    @Test
    fun cardDepthSearchResultsMatchAllVisibleControls() {
        val keys = listOf(
            "card-depth-effect",
            "card-depth-edge",
            "card-depth-sheen",
            "card-depth-edge-coverage",
            "card-depth-fine-tune",
            "card-depth-surface-posters",
            "card-depth-surface-continue-watching",
            "card-depth-surface-episode-cards",
            "card-depth-surface-cast",
            "card-depth-surface-trailers",
        )
        keys.forEach { key ->
            assertEquals(
                2,
                settingsSearchAnchor(SettingsPage.PosterCustomization, key, key, "")?.sectionItemIndex,
                key,
            )
        }
    }

    @Test
    fun thresholdDetailSearchTermsDoNotCreatePhantomRows() {
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackUpNext, "playback-threshold-percent", "Threshold percentage", ""))
        assertNull(settingsSearchAnchor(SettingsPage.PlaybackUpNext, "playback-threshold-minutes", "Minutes before end", ""))
    }

    @Test
    fun appearanceThemeSelectorHasExactAnchor() {
        assertEquals(
            0,
            settingsSearchAnchor(SettingsPage.Appearance, "theme", "Theme", "")?.sectionItemIndex,
        )
    }

    @Test
    fun advancedCacheActionsFollowOptionalDiagnosticSections() {
        val expectedIndex = 2 +
            (if (DnsSettingsRepository.isSupported) 1 else 0) +
            (if (SentrySettingsRepository.isSupported) 1 else 0)
        listOf("advanced-clear-cw-cache", "advanced-clear-metadata-cache").forEach { key ->
            assertEquals(
                expectedIndex,
                settingsSearchAnchor(SettingsPage.Advanced, key, key, "")?.sectionItemIndex,
                key,
            )
        }
    }

    @Test
    fun allTrackingProviderCardsHaveExactAnchors() {
        listOf(
            "trakt-authentication",
            "simkl-authentication",
            "mdblist-authentication",
            "anilist-authentication",
            "wetrakr-authentication",
            "publicmetadb-authentication",
        ).forEach { key ->
            assertEquals(
                0,
                settingsSearchAnchor(SettingsPage.TraktAuthentication, key, key, "")?.sectionItemIndex,
                key,
            )
        }
    }

}
