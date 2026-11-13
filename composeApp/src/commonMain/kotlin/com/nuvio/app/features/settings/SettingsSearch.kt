package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.DnsSettingsRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.isLiquidGlassSurfaceEffectivelyEnabled
import com.nuvio.app.core.ui.glass.isLiquidGlassSupported
import com.nuvio.app.core.ui.glass.liquidGlassForSurface
import com.nuvio.app.core.ui.glass.liquidGlassShape
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.effectiveDownmixEnabled
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.isIos
import com.nuvio.app.supportsPosterNavigationMotion
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal sealed class SettingsSearchTarget {
    data class Page(
        val page: SettingsPage,
        val anchor: SettingsSearchAnchor? = null,
    ) : SettingsSearchTarget()
    object Collections : SettingsSearchTarget()
    object SwitchProfile : SettingsSearchTarget()
    object CheckForUpdates : SettingsSearchTarget()
}

internal data class SettingsSearchAnchor(
    val page: SettingsPage,
    val key: String,
    val title: String,
    val description: String,
    val sectionItemIndex: Int,
) {
    fun matches(currentPage: SettingsPage, requestedKey: String?): Boolean =
        page == currentPage && key == requestedKey
}

internal fun settingsSearchAnchor(
    page: SettingsPage,
    key: String,
    title: String,
    description: String,
    debridCanResolvePlayableLinks: Boolean? = null,
    debridCanChooseResolverProvider: Boolean? = null,
    debridInstantPlaybackEnabled: Boolean? = null,
    liveTvHasPlaylist: Boolean? = null,
    continueWatchingPosterStyle: Boolean? = null,
    continueWatchingEpisodeThumbnails: Boolean? = null,
): SettingsSearchAnchor? {
    val sectionItemIndex = when (page) {
        SettingsPage.Appearance ->
            if (key in AppearanceSettingsSearchAnchorKeys) 1 else return null
        SettingsPage.Layout ->
            LayoutSettingsSearchAnchorItemIndexes[key] ?: return null
        SettingsPage.Account ->
            if (key in AccountSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.ContentDiscovery ->
            if (key == "recent-searches") 0 else return null
        SettingsPage.Streams ->
            if (key in StreamsSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.MetaScreen -> when (key) {
            "meta-overview", "meta-actions", "meta-details", "meta-trailers", "meta-cast",
            "meta-episodes", "meta-production", "meta-more-like-this", "meta-collection", "meta-comments" -> 1
            else -> if (key in MetaScreenSettingsSearchAnchorKeys) 0 else return null
        }
        SettingsPage.TmdbEnrichment -> when (key) {
            "tmdb-enable", "tmdb-api-key" -> 0
            "tmdb-language" -> 1
            else -> if (key in TmdbModuleSettingsSearchAnchorKeys) 2 else return null
        }
        SettingsPage.MdbListRatings -> when (key) {
            "mdb-enable", "mdb-show-on-hero" -> 0
            "mdb-api-key" -> 1
            else -> if (key in MdbListProviderSettingsSearchAnchorKeys) 2 else return null
        }
        SettingsPage.Omdb ->
            if (key == "omdb-api-key") 0 else return null
        SettingsPage.Homescreen ->
            if (key in HomescreenSettingsSearchAnchorKeys) 1 else return null
        SettingsPage.PosterCustomization -> when (key) {
            "poster-width", "poster-radius", "poster-landscape", "poster-hide-labels",
            "poster-always-show-landscape-clearlogo" -> 0
            "card-depth-effect", "card-depth-edge", "card-depth-sheen", "card-depth-edge-coverage",
            "card-depth-fine-tune" -> 2
            else -> if (key.startsWith("card-depth-surface-")) 2 else return null
            else -> return null
        }
        SettingsPage.ContinueWatching -> when (key) {
            "continue-watching-show-continue-watching" -> 0
            "continue-watching-style" -> 1
            "continue-watching-episode-thumbnails" -> if (continueWatchingPosterStyle == false) 2 else return null
            "continue-watching-up-next", "continue-watching-unaired-next-up" -> 2
            "continue-watching-blur-next-up" -> if (continueWatchingPosterStyle == false && continueWatchingEpisodeThumbnails == true) 2 else return null
            "continue-watching-resume-prompt" -> 3
            "continue-watching-sort-mode" -> 4
            else -> return null
        }
        SettingsPage.Debrid -> when (key) {
            "debrid-cloud-library", "debrid-link-resolving" -> 0
            "debrid-provider-torbox", "debrid-provider-premiumize" -> 1
            "debrid-resolve-provider" -> if (
                debridCanResolvePlayableLinks == true && debridCanChooseResolverProvider == true
            ) 0 else return null
            "debrid-instant-playback" -> if (debridCanResolvePlayableLinks == true) 2 else return null
            "debrid-instant-playback-count" -> if (
                debridCanResolvePlayableLinks == true && debridInstantPlaybackEnabled == true
            ) 2 else return null
            "debrid-max-results", "debrid-sort-results", "debrid-resolution-limit",
            "debrid-quality-limit", "debrid-size-range",
            "debrid-preferred-resolutions", "debrid-required-resolutions", "debrid-excluded-resolutions",
            "debrid-preferred-qualities", "debrid-required-qualities", "debrid-excluded-qualities",
            "debrid-preferred-visual-tags", "debrid-required-visual-tags", "debrid-excluded-visual-tags",
            "debrid-preferred-audio-tags", "debrid-required-audio-tags", "debrid-excluded-audio-tags",
            "debrid-preferred-channels", "debrid-required-channels", "debrid-excluded-channels",
            "debrid-preferred-encodes", "debrid-required-encodes", "debrid-excluded-encodes",
            "debrid-preferred-languages", "debrid-required-languages", "debrid-excluded-languages",
            "debrid-required-release-groups", "debrid-excluded-release-groups" ->
                if (debridCanResolvePlayableLinks == true) 3 else return null
            "debrid-name-template", "debrid-description-template" ->
                if (debridCanResolvePlayableLinks == true) 4 else return null
            else -> return null
        }
        SettingsPage.LiveTv -> when (key) {
            "live-tv-navigation" -> if (liveTvHasPlaylist == true) 0 else return null
            "live-tv-reload-channels", "live-tv-reload-epg" ->
                if (liveTvHasPlaylist == true) 1 else return null
            "live-tv-xtream-codes", "live-tv-stalker-portal", "live-tv-stalker-mac" ->
                if (liveTvHasPlaylist == true) 2 else if (liveTvHasPlaylist == false) 0 else return null
            "live-tv-playlist", "live-tv-playlist-url", "live-tv-local-playlist", "live-tv-playlist-name" ->
                if (liveTvHasPlaylist == true) 3 else if (liveTvHasPlaylist == false) 1 else return null
            "live-tv-epg-auto", "live-tv-epg-url", "live-tv-epg-add", "live-tv-epg-refresh" ->
                if (liveTvHasPlaylist == true) 4 else if (liveTvHasPlaylist == false) 2 else return null
            else -> return null
        }
        SettingsPage.DebugLogs ->
            if (key in DebugLogsSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.DiscordRpc -> when (key) {
            "discord-login" -> 0
            "discord-enable", "discord-activity-type", "discord-status" -> 1
            "discord-show-title-as-name", "discord-show-poster", "discord-show-playback-state",
            "discord-show-timestamps", "discord-hide-on-pause", "discord-show-imdb-button" -> 2
            "discord-application-id" -> 3
            else -> return null
        }
        SettingsPage.TraktAuthentication -> when (key) {
            "trakt-authentication", "simkl-authentication", "mdblist-authentication" -> 0
            "private-viewing" -> 1
            "trakt-library-source", "trakt-watch-progress", "trakt-more-like-this-source" -> 2
            "trakt-continue-watching-window", "trakt-comments" -> 4
            else -> return null
        }
        SettingsPage.PlaybackPlayer ->
            if (key in PlaybackPlayerSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackStreamSelection ->
            if (key in PlaybackStreamSelectionSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackUpNext ->
            if (key in PlaybackUpNextSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackSkipSegments ->
            if (key in PlaybackSkipSegmentsSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackInterface ->
            if (key in PlaybackInterfaceSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackAudio ->
            if (key in PlaybackAudioSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackSubtitles ->
            if (key in PlaybackSubtitlesSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackVideo ->
            if (key in PlaybackVideoSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackBuffer ->
            if (key in PlaybackBufferSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.PlaybackP2p ->
            if (key in PlaybackP2pSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.Downloads ->
            if (key in DownloadsSettingsSearchAnchorKeys) 0 else return null
        SettingsPage.Notifications -> when (key) {
            "episode-release-alerts" -> 0
            "notification-test" -> 1
            else -> return null
        }
        SettingsPage.Advanced -> when (key) {
            "advanced-remember-last-profile" -> 0
            "advanced-dns-provider" -> if (DnsSettingsRepository.isSupported) 1 else return null
            "advanced-sentry-reports" -> if (SentrySettingsRepository.isSupported) {
                1 + if (DnsSettingsRepository.isSupported) 1 else 0
            } else return null
            else -> return null
        }
        else -> return null
    }
    return SettingsSearchAnchor(page, key, title, description, sectionItemIndex)
}

private val TmdbModuleSettingsSearchAnchorKeys = setOf(
    "tmdb-trailers",
    "tmdb-artwork",
    "tmdb-basic-info",
    "tmdb-details",
    "tmdb-credits",
    "tmdb-companies",
    "tmdb-networks",
    "tmdb-episodes",
    "tmdb-season-posters",
    "tmdb-more-like-this",
    "tmdb-collections",
)

private val MdbListProviderSettingsSearchAnchorKeys = setOf(
    "mdb-imdb",
    "mdb-tmdb",
    "mdb-tomatoes",
    "mdb-metacritic",
    "mdb-trakt",
    "mdb-letterboxd",
    "mdb-audience",
)

private val AccountSettingsSearchAnchorKeys = setOf("account-status", "account-sign-out", "account-delete")

private val StreamsSettingsSearchAnchorKeys = setOf(
    "stream-addon-logo",
    "stream-background",
    "stream-size-badges",
    "stream-badge-position",
    "stream-badge-urls",
)

private val HomescreenSettingsSearchAnchorKeys = setOf(
    "home-hero",
    "home-catalog-type",
    "home-hide-unreleased",
)

private val DebugLogsSettingsSearchAnchorKeys = setOf(
    "debug-copy-logs",
    "debug-clear-logs",
    "debug-log-viewer",
)

private val MetaScreenSettingsSearchAnchorKeys = setOf(
    "meta-background-mode",
    "meta-poster-transition",
    "meta-tabs",
    "meta-overall-ratings",
    "meta-episode-ratings",
    "meta-episode-cards",
    "meta-shuffle",
    "meta-blur-episodes",
)

private val AppearanceSettingsSearchAnchorKeys = setOf("amoled", "app-icon", "app-language")

// These indexes match the four LazyListScope items in layoutSettingsContent:
// home controls, streams navigation, detail-page navigation, and navigation-bar controls.
private val LayoutSettingsSearchAnchorItemIndexes = mapOf(
    "dynamic-artwork-background" to 0,
    "catalog-accent" to 0,
    "liquid-glass" to 3,
    "navigation-bar" to 3,
)

private val DownloadsSettingsSearchAnchorKeys = setOf(
    "downloads-allow-mobile-data",
    "downloads-show-download-button",
    "download-location",
)

private val AdvancedSettingsSearchAnchorKeys = setOf(
    "advanced-remember-last-profile",
    "advanced-dns-provider",
    "advanced-sentry-reports",
)

private val PlaybackPlayerSettingsSearchAnchorKeys = setOf(
    "playback-player-preference",
    "playback-external-player-app",
    "playback-external-player-forward-subtitles",
    "playback-external-player-send-skip-segments",
    "playback-engine",
    "playback-auto-switch-engine-on-error",
)

private val PlaybackStreamSelectionSettingsSearchAnchorKeys = setOf(
    "playback-stream-reuse-last-link",
    "playback-stream-cache-duration",
    "playback-stream-selection-mode",
    "playback-stream-regex-pattern",
    "playback-stream-timeout",
    "playback-stream-source-scope",
    "playback-stream-allowed-addons",
    "playback-stream-allowed-plugins",
)

private val PlaybackInterfaceSettingsSearchAnchorKeys = setOf(
    "playback-interface-legacy-layout",
    "playback-interface-title-logo",
    "playback-interface-control-glass",
    "playback-interface-skip-buttons",
    "playback-interface-toolbar-layout",
    "playback-interface-legacy-top-buttons",
    "playback-interface-legacy-bottom-buttons",
    "playback-interface-loading-overlay",
    "playback-interface-exo-chapters",
    "playback-interface-loading-status",
    "playback-interface-pause-overlay",
    "playback-interface-parental-guide",
    "playback-interface-movie-recommendations",
    "playback-interface-mini-player",
    "playback-interface-seek-preview",
    "playback-interface-touch-gestures",
    "playback-interface-swipe-to-seek",
    "playback-interface-hold-to-speed",
    "playback-interface-hold-speed-value",
    "playback-interface-skip-backward",
    "playback-interface-skip-forward",
)

private val PlaybackAudioSettingsSearchAnchorKeys = setOf(
    "playback-audio-preferred-language",
    "playback-audio-secondary-language",
    "playback-audio-night-mode-button",
    "playback-audio-remember-delay",
    "playback-audio-downmix",
    "playback-audio-downmix-channels",
    "playback-audio-downmix-maintain-volume",
    "playback-audio-ios-output",
)

private val PlaybackSubtitlesSettingsSearchAnchorKeys = setOf(
    "playback-preferred-subtitles",
    "playback-secondary-subtitles",
    "playback-subtitle-font",
    "playback-subtitle-shadow",
    "playback-libass",
    "playback-libass-render-mode",
    "playback-libass-render",
    "playback-libmpv-ass",
)

private val PlaybackVideoSettingsSearchAnchorKeys = setOf(
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

private val PlaybackBufferSettingsSearchAnchorKeys = setOf(
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

private val PlaybackP2pSettingsSearchAnchorKeys = setOf(
    "playback-p2p-enabled",
    "playback-p2p-hide-stats",
    "playback-p2p-profile",
    "playback-p2p-cache-size",
    "playback-p2p-clear-cache",
)

private val PlaybackSkipSegmentsSettingsSearchAnchorKeys = setOf(
    "playback-skip-intro-enabled",
    "playback-skip-intro-offset",
    "playback-auto-skip-segments",
    "playback-anime-skip-enabled",
    "playback-anime-skip-client-id",
    "playback-intro-submit-enabled",
    "playback-introdb-api-key",
)

private val PlaybackUpNextSettingsSearchAnchorKeys = setOf(
    "playback-up-next-auto-play",
    "playback-up-next-fallback",
    "playback-up-next-prefer-binge",
    "playback-up-next-reuse-binge",
    "playback-up-next-preload",
    "playback-up-next-threshold-mode",
)

internal fun settingsSearchAnchorListIndex(
    anchor: SettingsSearchAnchor,
    isTablet: Boolean,
    showInternalHeader: Boolean,
): Int = anchor.sectionItemIndex + when {
    isTablet && showInternalHeader -> 1
    isTablet -> 0
    else -> 1 // Phone has either a sticky header or top spacer.
}

internal val SettingsSearchTarget.icon: ImageVector
    get() = when (this) {
        is SettingsSearchTarget.Page -> page.icon
        SettingsSearchTarget.Collections -> Icons.Default.CollectionsBookmark
        SettingsSearchTarget.SwitchProfile -> Icons.Default.People
        SettingsSearchTarget.CheckForUpdates -> Icons.Default.CloudDownload
    }

internal data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val description: String,
    val page: String,
    val section: String,
    val category: String,
    val icon: ImageVector,
    val target: SettingsSearchTarget,
    val searchTerms: List<String> = emptyList(),
) {
    val searchableText: String = (listOf(title, description, page, section, category, key) + searchTerms)
        .joinToString(separator = " ")
        .lowercase()

    val contextLabel: String = listOf(page, section)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(separator = " - ")
}

@Composable
internal fun settingsSearchEntries(
    isTablet: Boolean,
    pluginsEnabled: Boolean,
    supportersContributorsPageEnabled: Boolean,
    accountDeletionEnabled: Boolean,
    personalMediaAddonCopyEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
    debridSettings: DebridSettings,
    liveTvUiState: LiveTvUiState,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    continueWatchingEpisodeThumbnails: Boolean,
): List<SettingsSearchEntry> {
    val authState by remember { AuthRepository.state }.collectAsStateWithLifecycle()
    val accountCategory = stringResource(SettingsCategory.You.labelRes)
    val generalCategory = stringResource(SettingsCategory.Look.labelRes)
    val aboutCategory = stringResource(SettingsCategory.System.labelRes)
    val advancedCategory = stringResource(SettingsCategory.System.labelRes)

    val accountPage = stringResource(Res.string.compose_settings_page_account)
    val trackingPage = stringResource(Res.string.compose_settings_page_tracking)
    val appearancePage = stringResource(Res.string.settings_appearance_title)
    val layoutPage = stringResource(Res.string.compose_settings_page_layout)
    val advancedPage = stringResource(Res.string.compose_settings_page_advanced)
    val contentDiscoveryPage = stringResource(Res.string.compose_settings_page_content_discovery)
    val playbackPage = stringResource(Res.string.compose_settings_page_playback)
    val streamsPage = stringResource(Res.string.compose_settings_page_streams)
    val integrationsPage = stringResource(Res.string.compose_settings_page_integrations)
    val notificationsPage = stringResource(Res.string.compose_settings_page_notifications)
    val supportersPage = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesPage = stringResource(Res.string.compose_settings_page_licenses_attributions)
    val whatsNewPage = stringResource(Res.string.compose_settings_page_whats_new)
    val homeLayoutPage = stringResource(Res.string.compose_settings_page_homescreen)
    val detailPage = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingPage = stringResource(Res.string.compose_settings_page_continue_watching)
    val posterStylePage = stringResource(Res.string.compose_settings_page_poster_customization)
    val downloadsPage = stringResource(Res.string.compose_settings_root_downloads_title)
    val addonsPage = stringResource(Res.string.compose_settings_page_addons)
    val pluginsPage = stringResource(Res.string.compose_settings_page_plugins)
    val collectionsPage = stringResource(Res.string.collections_header)
    val tmdbPage = stringResource(Res.string.compose_settings_page_tmdb_enrichment)
    val mdbListPage = stringResource(Res.string.compose_settings_page_mdblist_ratings)
    val omdbPage = stringResource(Res.string.compose_settings_page_omdb)

    val categories = SettingsCategory.entries.associateWith { stringResource(it.labelRes) }
    val pageTitles = SettingsPage.entries.associateWith { stringResource(it.titleRes) }
    val debridPage = pageTitles.getValue(SettingsPage.Debrid)
    val liveTvPage = pageTitles.getValue(SettingsPage.LiveTv)
    val discordRpcPage = pageTitles.getValue(SettingsPage.DiscordRpc)
    val debugLogsPage = pageTitles.getValue(SettingsPage.DebugLogs)
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val cardDepthStyleUiState by remember {
        CardDepthStyleRepository.ensureLoaded()
        CardDepthStyleRepository.uiState
    }.collectAsStateWithLifecycle()
    val tabBarBehavior by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.tabBarBehavior
    }.collectAsStateWithLifecycle()
    val playbackSections = visiblePlaybackSections(isIos, playerSettings.androidPlaybackEngine, P2pSettingsRepository.isVisible)
    val entries = mutableListOf<SettingsSearchEntry>()

    fun add(
        key: String,
        title: String,
        description: String = "",
        page: String = title,
        section: String = "",
        category: String = generalCategory,
        target: SettingsSearchTarget,
        searchTerms: List<String> = emptyList(),
    ) {
        entries += SettingsSearchEntry(
            key = key,
            title = title,
            description = description,
            page = page,
            section = section,
            category = (target as? SettingsSearchTarget.Page)?.page?.category?.let(categories::getValue) ?: category,
            icon = target.icon,
            target = target,
            searchTerms = searchTerms,
        )
    }

    fun addPage(
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        category: String = generalCategory,
        searchTerms: List<String> = emptyList(),
    ) {
        add(
            key = key,
            title = title,
            description = description,
            page = title,
            category = category,
            target = SettingsSearchTarget.Page(page),
            searchTerms = searchTerms,
        )
    }

    fun addRow(
        page: SettingsPage,
        key: String,
        title: String,
        description: String = "",
        pageLabel: String,
        section: String,
        category: String = generalCategory,
        searchTerms: List<String> = emptyList(),
    ) {
        if (page.parentPage == SettingsPage.Playback && playbackSections.none { it.page == page }) return
        add(
            key = key,
            title = title,
            description = description,
            page = if (page.parentPage == SettingsPage.Playback) playbackPage else pageLabel,
            section = section,
            category = category,
            target = SettingsSearchTarget.Page(
                page = page,
                anchor = settingsSearchAnchor(
                    page = page,
                    key = key,
                    title = title,
                    description = description,
                    debridCanResolvePlayableLinks = debridSettings.canResolvePlayableLinks,
                    debridCanChooseResolverProvider = debridSettings.canResolvePlayableLinks &&
                        debridSettings.resolverServices.size > 1 && debridSettings.activeResolverCredential != null,
                    debridInstantPlaybackEnabled = debridSettings.instantPlaybackPreparationLimit > 0,
                    liveTvHasPlaylist = liveTvUiState.hasPlaylist,
                    continueWatchingPosterStyle = continueWatchingStyle == ContinueWatchingSectionStyle.Poster,
                    continueWatchingEpisodeThumbnails = continueWatchingEpisodeThumbnails,
                ),
            ),
            searchTerms = searchTerms,
        )
    }

    if (switchProfileAvailable) {
        add(
            key = "switch-profile",
            title = stringResource(Res.string.compose_settings_root_switch_profile_title),
            description = stringResource(Res.string.compose_settings_root_switch_profile_description),
            page = accountPage,
            section = stringResource(Res.string.compose_settings_root_account_section),
            category = accountCategory,
            target = SettingsSearchTarget.SwitchProfile,
        )
    }
    addPage(
        page = SettingsPage.Account,
        key = "account",
        title = accountPage,
        description = stringResource(Res.string.compose_settings_root_account_description),
        category = accountCategory,
        searchTerms = listOf(
            "profile", "sign in", "sign out", "account details",
            "manage account", "user account", "user profile", "log in", "login",
            "log out", "logout", "authentication", "account settings",
            "profile management", "account access",
        ),
    )
    addPage(
        page = SettingsPage.TraktAuthentication,
        key = "tracking",
        title = trackingPage,
        description = stringResource(Res.string.compose_settings_root_tracking_description),
        category = accountCategory,
        searchTerms = listOf(
            "watch history", "watch progress sync", "sync viewing activity",
            "trakt", "simkl", "mdblist", "scrobbling", "tracking services",
        ),
    )
    addPage(
        page = SettingsPage.Appearance,
        key = "appearance",
        title = appearancePage,
        description = stringResource(Res.string.settings_appearance_description),
        searchTerms = listOf(
            "theme", "dark mode", "light mode", "amoled", "colors", "app icon", "language", "look",
            "color scheme", "app appearance", "display theme", "localization", "translation",
            "accent color", "icon style", "visual settings",
        ),
    )
    addPage(
        page = SettingsPage.Layout,
        key = "layout",
        title = layoutPage,
        description = stringResource(Res.string.compose_settings_root_layout_description),
        searchTerms = listOf(
            "navigation", "navigation tabs", "bottom navigation", "tab bar",
            "catalog layout", "screen layout",
            "appearance layout", "interface layout", "tab navigation", "bottom bar",
            "navigation bar", "sidebar navigation", "grid view", "list view", "layout settings",
        ),
    )
    addPage(
        page = SettingsPage.About,
        key = "about",
        title = pageTitles.getValue(SettingsPage.About),
        description = stringResource(Res.string.compose_settings_root_about_description),
    )
    playbackSections.forEach { section ->
        addRow(
            page = section.page,
            key = "playback-page-${section.name}",
            title = pageTitles.getValue(section.page),
            description = stringResource(section.descriptionRes),
            pageLabel = playbackPage,
            section = pageTitles.getValue(section.page),
        )
    }

    val playbackPlayerSection = stringResource(Res.string.settings_playback_section_player)
    val playbackDecoderSection = stringResource(Res.string.settings_playback_section_decoder)
    addRow(
        page = SettingsPage.PlaybackPlayer,
        key = "playback-player-preference",
        title = stringResource(Res.string.settings_playback_player_preference),
        description = stringResource(Res.string.settings_playback_player_preference_description),
        pageLabel = playbackPage,
        section = playbackPlayerSection,
        searchTerms = listOf("internal player", "external player", "default player"),
    )
    if (isIos && playerSettings.externalPlayerEnabled) {
        addRow(
            page = SettingsPage.PlaybackPlayer,
            key = "playback-external-player-app",
            title = stringResource(Res.string.settings_playback_external_player_app),
            pageLabel = playbackPage,
            section = playbackPlayerSection,
            searchTerms = listOf("vlc", "infuse", "choose external player"),
        )
    }
    if (!isIos) {
        if (playerSettings.externalPlayerEnabled) {
            addRow(
                page = SettingsPage.PlaybackPlayer,
                key = "playback-external-player-forward-subtitles",
                title = stringResource(Res.string.settings_playback_external_player_forward_subtitles),
                description = stringResource(Res.string.settings_playback_external_player_forward_subtitles_description),
                pageLabel = playbackPage,
                section = playbackPlayerSection,
                searchTerms = listOf("captions", "subtitle forwarding"),
            )
            addRow(
                page = SettingsPage.PlaybackPlayer,
                key = "playback-external-player-send-skip-segments",
                title = stringResource(Res.string.settings_playback_external_player_send_skip_segments),
                description = stringResource(Res.string.settings_playback_external_player_send_skip_segments_description),
                pageLabel = playbackPage,
                section = playbackPlayerSection,
                searchTerms = listOf("skip intro", "skip credits", "skip segments"),
            )
        }
        addRow(
            page = SettingsPage.PlaybackPlayer,
            key = "playback-engine",
            title = stringResource(Res.string.settings_playback_engine),
            pageLabel = playbackPage,
            section = playbackDecoderSection,
            searchTerms = listOf("decoder", "exoplayer", "exo player", "mpv", "libmpv", "automatic engine"),
        )
        addRow(
            page = SettingsPage.PlaybackPlayer,
            key = "playback-auto-switch-engine-on-error",
            title = stringResource(Res.string.settings_playback_auto_switch_engine_on_error),
            description = stringResource(Res.string.settings_playback_auto_switch_engine_on_error_description),
            pageLabel = playbackPage,
            section = playbackDecoderSection,
            searchTerms = listOf("decoder fallback", "switch player", "engine fallback"),
        )
    }

    val streamSelectionSection = stringResource(Res.string.settings_playback_section_stream_selection)
    val streamAutoPlaySection = stringResource(Res.string.settings_playback_section_stream_auto_play)
    addRow(
        page = SettingsPage.PlaybackStreamSelection,
        key = "playback-stream-reuse-last-link",
        title = stringResource(Res.string.settings_playback_reuse_last_link),
        description = stringResource(Res.string.settings_playback_reuse_last_link_description),
        pageLabel = playbackPage,
        section = streamSelectionSection,
        searchTerms = listOf("reuse source", "last working stream", "cached stream", "auto play last link"),
    )
    if (playerSettings.streamReuseLastLinkEnabled) {
        addRow(
            page = SettingsPage.PlaybackStreamSelection,
            key = "playback-stream-cache-duration",
            title = stringResource(Res.string.settings_playback_last_link_cache_duration),
            pageLabel = playbackPage,
            section = streamSelectionSection,
            searchTerms = listOf("cache expiry", "cache lifetime", "last stream retention"),
        )
    }
    addRow(
        page = SettingsPage.PlaybackStreamSelection,
        key = "playback-stream-selection-mode",
        title = stringResource(Res.string.settings_playback_stream_selection_mode),
        pageLabel = playbackPage,
        section = streamAutoPlaySection,
        searchTerms = listOf("automatic stream", "auto select", "manual stream selection", "first source", "regex"),
    )
    if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
        addRow(
            page = SettingsPage.PlaybackStreamSelection,
            key = "playback-stream-regex-pattern",
            title = stringResource(Res.string.settings_playback_regex_pattern),
            description = stringResource(Res.string.settings_playback_stream_selection_mode_regex_description),
            pageLabel = playbackPage,
            section = streamAutoPlaySection,
            searchTerms = listOf("regular expression", "pattern matching", "match source text"),
        )
    }
    addRow(
        page = SettingsPage.PlaybackStreamSelection,
        key = "playback-stream-timeout",
        title = stringResource(Res.string.settings_playback_stream_timeout),
        description = stringResource(Res.string.settings_playback_stream_timeout_description),
        pageLabel = playbackPage,
        section = streamAutoPlaySection,
        searchTerms = listOf("wait time", "provider timeout", "selection delay", "seconds"),
    )
    addRow(
        page = SettingsPage.PlaybackStreamSelection,
        key = "playback-stream-source-scope",
        title = stringResource(Res.string.settings_playback_source_scope),
        pageLabel = playbackPage,
        section = streamAutoPlaySection,
        searchTerms = listOf("source providers", "addon sources", "plugin sources", "where auto play searches"),
    )
    if (playerSettings.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
        addRow(
            page = SettingsPage.PlaybackStreamSelection,
            key = "playback-stream-allowed-addons",
            title = stringResource(Res.string.settings_playback_allowed_addons),
            pageLabel = playbackPage,
            section = streamAutoPlaySection,
            searchTerms = listOf("installed addons", "provider allow list", "addon filter"),
        )
    }
    if (pluginsEnabled && playerSettings.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) {
        addRow(
            page = SettingsPage.PlaybackStreamSelection,
            key = "playback-stream-allowed-plugins",
            title = stringResource(Res.string.settings_playback_allowed_plugins),
            pageLabel = playbackPage,
            section = streamAutoPlaySection,
            searchTerms = listOf("enabled plugins", "plugin provider filter", "plugin allow list"),
        )
    }

    val skipSegmentsSection = stringResource(Res.string.settings_playback_section_skip_segments)
    addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-skip-intro-enabled", title = stringResource(Res.string.settings_playback_skip_intro_outro_recap), description = stringResource(Res.string.settings_playback_skip_intro_outro_recap_description), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("skip intro", "skip outro", "recap", "credits"))
    addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-skip-intro-offset", title = stringResource(Res.string.settings_playback_skip_intro_offset), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("intro timing", "skip offset", "seconds earlier", "seconds later"))
    addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-auto-skip-segments", title = stringResource(Res.string.settings_playback_auto_skip_segments), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("automatic segment skipping", "intro credits recap selection"))
    addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-anime-skip-enabled", title = stringResource(Res.string.settings_playback_anime_skip), description = stringResource(Res.string.settings_playback_anime_skip_description), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("aniskip", "anime openings", "anime endings"))
    if (playerSettings.animeSkipEnabled) addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-anime-skip-client-id", title = stringResource(Res.string.settings_playback_anime_skip_client_id), description = stringResource(Res.string.settings_playback_anime_skip_client_id_description), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("aniskip client id", "oauth client id"))
    addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-intro-submit-enabled", title = stringResource(Res.string.settings_playback_intro_submit_enabled), description = stringResource(Res.string.settings_playback_intro_submit_enabled_description), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("introdb", "submit intro timestamps", "contribute skip times"))
    if (playerSettings.introSubmitEnabled) addRow(page = SettingsPage.PlaybackSkipSegments, key = "playback-introdb-api-key", title = stringResource(Res.string.settings_playback_introdb_api_key), description = stringResource(Res.string.settings_playback_introdb_api_key_description), pageLabel = playbackPage, section = skipSegmentsSection, searchTerms = listOf("introdb api token", "intro database credential"))


    val playbackInterfaceSection = stringResource(Res.string.settings_playback_page_interface)
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-legacy-layout", title = stringResource(Res.string.settings_playback_legacy_layout), description = stringResource(Res.string.settings_playback_legacy_layout_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("classic player layout", "old player controls"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-title-logo", title = stringResource(Res.string.settings_playback_show_title_logo), description = stringResource(Res.string.settings_playback_show_title_logo_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("movie logo", "title branding"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-control-glass", title = stringResource(Res.string.settings_playback_control_glass), description = stringResource(Res.string.settings_playback_control_glass_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("glass controls", "liquid glass player controls"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-skip-buttons", title = stringResource(Res.string.settings_playback_show_skip_buttons), description = stringResource(Res.string.settings_playback_show_skip_buttons_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("seek buttons", "forward backward buttons"))
    if (!playerSettings.useLegacyPlayerLayout) addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-toolbar-layout", title = stringResource(Res.string.settings_playback_toolbar_layout), description = stringResource(Res.string.settings_playback_toolbar_layout_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("player toolbar", "control arrangement"))
    if (playerSettings.useLegacyPlayerLayout) {
        addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-legacy-top-buttons", title = stringResource(Res.string.settings_playback_legacy_top_buttons), description = stringResource(Res.string.settings_playback_legacy_top_buttons_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("classic top buttons", "legacy controls"))
        addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-legacy-bottom-buttons", title = stringResource(Res.string.settings_playback_legacy_bottom_buttons), description = stringResource(Res.string.settings_playback_legacy_bottom_buttons_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("classic bottom buttons", "legacy controls"))
    }
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-loading-overlay", title = stringResource(Res.string.settings_playback_show_loading_overlay), description = stringResource(Res.string.settings_playback_show_loading_overlay_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("buffering screen", "loading spinner overlay"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-exo-chapters", title = stringResource(Res.string.settings_playback_exo_chapters), description = stringResource(Res.string.settings_playback_exo_chapters_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("exoplayer chapters", "chapter markers"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-loading-status", title = stringResource(Res.string.playback_show_loading_status), description = stringResource(Res.string.playback_show_loading_status_sub), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("playback status", "loading details"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-pause-overlay", title = stringResource(Res.string.settings_playback_pause_overlay), description = stringResource(Res.string.settings_playback_pause_overlay_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("pause screen", "paused overlay"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-parental-guide", title = stringResource(Res.string.settings_playback_parental_guide), description = stringResource(Res.string.settings_playback_parental_guide_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("age rating", "content rating", "parental ratings"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-movie-recommendations", title = stringResource(Res.string.settings_playback_movie_recommendations), description = stringResource(Res.string.settings_playback_movie_recommendations_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("recommendations after movie", "related movies"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-mini-player", title = stringResource(Res.string.settings_playback_mini_player), description = stringResource(Res.string.settings_playback_mini_player_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("picture in picture", "miniplayer"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-seek-preview", title = stringResource(Res.string.settings_playback_seek_preview), description = stringResource(Res.string.settings_playback_seek_preview_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("thumbnail preview", "scrub preview", "seek thumbnails"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-touch-gestures", title = stringResource(Res.string.settings_playback_touch_gestures), description = stringResource(Res.string.settings_playback_touch_gestures_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("swipe controls", "touch controls", "gesture controls"))
    if (playerSettings.touchGesturesEnabled && !playerSettings.externalPlayerEnabled) addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-swipe-to-seek", title = stringResource(Res.string.settings_playback_swipe_to_seek), description = stringResource(Res.string.settings_playback_swipe_to_seek_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("swipe seeking", "drag to seek"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-hold-to-speed", title = stringResource(Res.string.settings_playback_hold_to_speed), description = stringResource(Res.string.settings_playback_hold_to_speed_description), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("press and hold speed", "temporary fast forward"))
    if (playerSettings.holdToSpeedEnabled && !playerSettings.externalPlayerEnabled) addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-hold-speed-value", title = stringResource(Res.string.settings_playback_hold_speed), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("hold speed multiplier", "playback speed while holding"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-skip-backward", title = stringResource(Res.string.settings_playback_skip_backward), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("rewind duration", "back skip seconds"))
    addRow(page = SettingsPage.PlaybackInterface, key = "playback-interface-skip-forward", title = stringResource(Res.string.settings_playback_skip_forward), pageLabel = playbackPage, section = playbackInterfaceSection, searchTerms = listOf("forward seek duration", "skip ahead seconds"))

    val upNextSection = stringResource(Res.string.settings_playback_section_next_episode)
    addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-auto-play", title = stringResource(Res.string.settings_playback_auto_play_next_episode), description = stringResource(Res.string.settings_playback_auto_play_next_episode_description), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("autoplay", "next show", "next tv episode"))
    if (playerSettings.streamAutoPlayNextEpisodeEnabled && playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
        addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-fallback", title = stringResource(Res.string.settings_playback_auto_play_next_episode_fallback), description = stringResource(Res.string.settings_playback_auto_play_next_episode_fallback_description), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("binge group failure", "fallback stream"))
    }
    addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-prefer-binge", title = stringResource(Res.string.settings_playback_prefer_binge_group), description = stringResource(Res.string.settings_playback_prefer_binge_group_description), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("same source", "same addon", "quality group"))
    if (playerSettings.streamAutoPlayPreferBingeGroup) {
        addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-reuse-binge", title = stringResource(Res.string.settings_playback_reuse_binge_group), description = stringResource(Res.string.settings_playback_reuse_binge_group_description), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("remember source", "reuse addon"))
    }
    addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-preload", title = stringResource(Res.string.settings_playback_preload_next_episode), description = stringResource(Res.string.settings_playback_preload_next_episode_description), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("prefetch", "background source search"))
    addRow(page = SettingsPage.PlaybackUpNext, key = "playback-up-next-threshold-mode", title = stringResource(Res.string.settings_playback_threshold_mode), pageLabel = playbackPage, section = upNextSection, searchTerms = listOf("next episode prompt", "when to show next episode", "percentage or minutes"))

    addPage(
        page = SettingsPage.Advanced,
        key = "advanced",
        title = advancedPage,
        description = stringResource(Res.string.compose_settings_root_advanced_description),
        category = advancedCategory,
    )

    addPage(
        page = SettingsPage.ContentDiscovery,
        key = "content-discovery",
        title = contentDiscoveryPage,
        description = stringResource(Res.string.compose_settings_root_content_discovery_description),
        searchTerms = listOf("sources", "catalogs", "providers", "addons", "plugins", "media servers", "live tv"),
    )
    addRow(
        page = SettingsPage.ContentDiscovery,
        key = "recent-searches",
        title = stringResource(Res.string.settings_content_discovery_recent_searches),
        description = stringResource(Res.string.settings_content_discovery_recent_searches_description),
        pageLabel = contentDiscoveryPage,
        section = stringResource(Res.string.settings_content_discovery_section_search),
    )
    addRow(
        page = SettingsPage.Downloads,
        key = "downloads-allow-mobile-data",
        title = stringResource(Res.string.downloads_allow_mobile_data_title),
        description = stringResource(Res.string.downloads_allow_mobile_data_description),
        pageLabel = downloadsPage,
        section = downloadsPage,
        searchTerms = listOf("cellular data", "mobile network", "data usage", "download over mobile data"),
    )
    addRow(
        page = SettingsPage.Downloads,
        key = "downloads-show-download-button",
        title = stringResource(Res.string.downloads_show_download_button_title),
        description = stringResource(Res.string.downloads_show_download_button_description),
        pageLabel = downloadsPage,
        section = downloadsPage,
        searchTerms = listOf("download action", "download control", "download icon", "save media"),
    )
    addRow(
        page = SettingsPage.Downloads,
        key = "download-location",
        title = stringResource(Res.string.settings_downloads_location_title),
        description = stringResource(Res.string.compose_settings_root_downloads_description),
        pageLabel = downloadsPage,
        section = downloadsPage,
        searchTerms = listOf("offline", "download manager", "storage", "saved files"),
    )
    addPage(
        page = SettingsPage.Playback,
        key = "playback",
        title = playbackPage,
        description = stringResource(Res.string.settings_playback_subtitle),
        searchTerms = listOf("video player", "audio", "subtitles", "captions", "buffer", "decoder", "streams", "playback speed"),
    )
    addPage(
        page = SettingsPage.Streams,
        key = "streams",
        title = streamsPage,
        description = stringResource(Res.string.compose_settings_root_streams_description),
    )
    addPage(
        page = SettingsPage.Integrations,
        key = "integrations",
        title = integrationsPage,
        description = stringResource(Res.string.compose_settings_root_integrations_description),
        searchTerms = listOf(
            "services", "providers", "external services", "api integrations",
            "connect services", "link accounts", "third party", "connected apps",
        ),
    )
    addPage(
        page = SettingsPage.Debrid,
        key = "debrid",
        title = debridPage,
        description = stringResource(Res.string.settings_debrid_experimental_notice),
        searchTerms = listOf(
            "real debrid", "premiumize", "torbox", "cloud library", "cloud downloads",
            "link resolver", "cached streams", "instant playback", "stream filtering",
            "resolutions", "quality filters", "release groups", "audio tags", "audio channels",
            "visual tags", "encoding", "language filters", "size limits", "result sorting",
        ),
    )
    addPage(
        page = SettingsPage.LiveTv,
        key = "live-tv",
        title = liveTvPage,
        description = stringResource(Res.string.live_tv_settings_description),
        searchTerms = listOf(
            "iptv", "m3u", "m3u8", "epg", "electronic program guide", "tv guide",
            "xtream codes", "stalker portal", "channels", "live channels", "playlist", "channel guide",
        ),
    )
    addPage(
        page = SettingsPage.DiscordRpc,
        key = "discord-rpc",
        title = discordRpcPage,
        description = stringResource(Res.string.discord_rpc_enable_description),
        searchTerms = listOf(
            "discord", "rpc", "rich presence", "now playing", "game status", "activity status",
            "show poster", "playback state", "timestamps", "application id",
        ),
    )
    addPage(
        page = SettingsPage.DebugLogs,
        key = "debug-logs",
        title = debugLogsPage,
        description = stringResource(Res.string.settings_advanced_debugging_log_viewer_description),
        searchTerms = listOf(
            "debug", "debugging", "logs", "log viewer", "logging", "diagnostics",
            "troubleshooting", "console", "copy logs", "clear logs", "log level",
        ),
    )
    addPage(
        page = SettingsPage.Notifications,
        key = "notifications",
        title = notificationsPage,
        description = stringResource(Res.string.compose_settings_root_notifications_description),
        searchTerms = listOf("alerts", "push notifications", "notification sound", "reminders", "episode alerts", "release notifications"),
    )
    if (supportersContributorsPageEnabled) {
        addPage(
            page = SettingsPage.SupportersContributors,
            key = "supporters",
            title = supportersPage,
            description = stringResource(Res.string.about_supporters_contributors_subtitle),
            category = aboutCategory,
        )
    }
    addPage(
        page = SettingsPage.LicensesAttributions,
        key = "licenses-attributions",
        title = licensesPage,
        description = stringResource(Res.string.about_licenses_attributions_subtitle),
        category = aboutCategory,
    )
    addPage(
        page = SettingsPage.WhatsNew,
        key = "whats-new",
        title = whatsNewPage,
        description = stringResource(Res.string.whats_new_root_description),
        category = aboutCategory,
    )
    listOf(
        PlaybackSearchRow("nuvio-license", stringResource(Res.string.settings_licenses_attributions_nuvio_title), stringResource(Res.string.settings_licenses_attributions_nuvio_license)),
        PlaybackSearchRow("tmdb-attribution", stringResource(Res.string.settings_licenses_attributions_tmdb_title), stringResource(Res.string.settings_licenses_attributions_tmdb_body)),
        PlaybackSearchRow("trakt-attribution", stringResource(Res.string.settings_licenses_attributions_trakt_title), stringResource(Res.string.settings_licenses_attributions_trakt_body)),
        PlaybackSearchRow("simkl-attribution", stringResource(Res.string.settings_licenses_attributions_simkl_title), stringResource(Res.string.settings_licenses_attributions_simkl_body)),
        PlaybackSearchRow("premiumize-attribution", stringResource(Res.string.settings_licenses_attributions_premiumize_title), stringResource(Res.string.settings_licenses_attributions_premiumize_body)),
        PlaybackSearchRow("torbox-attribution", stringResource(Res.string.settings_licenses_attributions_torbox_title), stringResource(Res.string.settings_licenses_attributions_torbox_body)),
        PlaybackSearchRow("mdblist-attribution", stringResource(Res.string.settings_licenses_attributions_mdblist_title), stringResource(Res.string.settings_licenses_attributions_mdblist_body)),
        PlaybackSearchRow("introdb-attribution", stringResource(Res.string.settings_licenses_attributions_introdb_title), stringResource(Res.string.settings_licenses_attributions_introdb_body)),
        PlaybackSearchRow("imdb-datasets", stringResource(Res.string.settings_licenses_attributions_imdb_title), stringResource(Res.string.settings_licenses_attributions_imdb_body)),
        PlaybackSearchRow(
            if (isIos) "mpvkit-license" else "exoplayer-license",
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_title)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_title)
            },
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_license)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_license)
            },
        ),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LicensesAttributions,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = licensesPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
        )
    }
    if (checkForUpdatesAvailable) {
        add(
            key = "check-updates",
            title = stringResource(Res.string.compose_settings_root_check_updates_title),
            description = stringResource(Res.string.compose_settings_root_check_updates_description),
            page = if (supportersContributorsPageEnabled) supportersPage else licensesPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            target = SettingsSearchTarget.CheckForUpdates,
        )
    }

    val debridMainSection = stringResource(Res.string.settings_debrid_section_title)
    val debridInstantSection = stringResource(Res.string.settings_debrid_section_instant_playback)
    val debridResultsSection = stringResource(Res.string.settings_debrid_section_result_management)
    val debridFormattingSection = stringResource(Res.string.settings_debrid_section_formatting)
    listOf(
        PlaybackSearchRow("cloud-library", stringResource(Res.string.settings_debrid_cloud_library), stringResource(Res.string.settings_debrid_cloud_library_description), debridMainSection),
        PlaybackSearchRow("link-resolving", stringResource(Res.string.settings_debrid_enable), stringResource(Res.string.settings_debrid_enable_description), debridMainSection),
        PlaybackSearchRow("resolve-provider", stringResource(Res.string.settings_debrid_resolve_with), stringResource(Res.string.settings_debrid_resolve_with_description), debridMainSection),
        PlaybackSearchRow("instant-playback", stringResource(Res.string.settings_debrid_prepare_instant_playback), stringResource(Res.string.settings_debrid_prepare_instant_playback_description), debridInstantSection),
        PlaybackSearchRow("instant-playback-count", stringResource(Res.string.settings_debrid_prepare_stream_count), sectionOverride = debridInstantSection),
        PlaybackSearchRow("max-results", stringResource(Res.string.settings_debrid_max_results), stringResource(Res.string.settings_debrid_max_results_desc), debridResultsSection),
        PlaybackSearchRow("sort-results", stringResource(Res.string.settings_debrid_sort_results), stringResource(Res.string.settings_debrid_sort_results_desc), debridResultsSection),
        PlaybackSearchRow("resolution-limit", stringResource(Res.string.settings_debrid_per_resolution_limit), stringResource(Res.string.settings_debrid_per_resolution_limit_desc), debridResultsSection),
        PlaybackSearchRow("quality-limit", stringResource(Res.string.settings_debrid_per_quality_limit), stringResource(Res.string.settings_debrid_per_quality_limit_desc), debridResultsSection),
        PlaybackSearchRow("size-range", stringResource(Res.string.settings_debrid_size_range), stringResource(Res.string.settings_debrid_size_range_desc), debridResultsSection),
        PlaybackSearchRow("name-template", stringResource(Res.string.settings_debrid_name_template), stringResource(Res.string.settings_debrid_name_template_description), debridFormattingSection),
        PlaybackSearchRow("description-template", stringResource(Res.string.settings_debrid_description_template), stringResource(Res.string.settings_debrid_description_template_description), debridFormattingSection),
        PlaybackSearchRow("preferred-resolutions", stringResource(Res.string.settings_debrid_rule_preferred_resolutions), stringResource(Res.string.settings_debrid_rule_preferred_resolutions_desc), debridResultsSection),
        PlaybackSearchRow("required-resolutions", stringResource(Res.string.settings_debrid_rule_required_resolutions), stringResource(Res.string.settings_debrid_rule_required_resolutions_desc), debridResultsSection),
        PlaybackSearchRow("excluded-resolutions", stringResource(Res.string.settings_debrid_rule_excluded_resolutions), stringResource(Res.string.settings_debrid_rule_excluded_resolutions_desc), debridResultsSection),
        PlaybackSearchRow("preferred-qualities", stringResource(Res.string.settings_debrid_rule_preferred_qualities), stringResource(Res.string.settings_debrid_rule_preferred_qualities_desc), debridResultsSection),
        PlaybackSearchRow("required-qualities", stringResource(Res.string.settings_debrid_rule_required_qualities), stringResource(Res.string.settings_debrid_rule_required_qualities_desc), debridResultsSection),
        PlaybackSearchRow("excluded-qualities", stringResource(Res.string.settings_debrid_rule_excluded_qualities), stringResource(Res.string.settings_debrid_rule_excluded_qualities_desc), debridResultsSection),
        PlaybackSearchRow("preferred-visual-tags", stringResource(Res.string.settings_debrid_rule_preferred_visual_tags), stringResource(Res.string.settings_debrid_rule_preferred_visual_tags_desc), debridResultsSection),
        PlaybackSearchRow("required-visual-tags", stringResource(Res.string.settings_debrid_rule_required_visual_tags), stringResource(Res.string.settings_debrid_rule_required_visual_tags_desc), debridResultsSection),
        PlaybackSearchRow("excluded-visual-tags", stringResource(Res.string.settings_debrid_rule_excluded_visual_tags), stringResource(Res.string.settings_debrid_rule_excluded_visual_tags_desc), debridResultsSection),
        PlaybackSearchRow("preferred-audio-tags", stringResource(Res.string.settings_debrid_rule_preferred_audio_tags), stringResource(Res.string.settings_debrid_rule_preferred_audio_tags_desc), debridResultsSection),
        PlaybackSearchRow("required-audio-tags", stringResource(Res.string.settings_debrid_rule_required_audio_tags), stringResource(Res.string.settings_debrid_rule_required_audio_tags_desc), debridResultsSection),
        PlaybackSearchRow("excluded-audio-tags", stringResource(Res.string.settings_debrid_rule_excluded_audio_tags), stringResource(Res.string.settings_debrid_rule_excluded_audio_tags_desc), debridResultsSection),
        PlaybackSearchRow("preferred-channels", stringResource(Res.string.settings_debrid_rule_preferred_channels), stringResource(Res.string.settings_debrid_rule_preferred_channels_desc), debridResultsSection),
        PlaybackSearchRow("required-channels", stringResource(Res.string.settings_debrid_rule_required_channels), stringResource(Res.string.settings_debrid_rule_required_channels_desc), debridResultsSection),
        PlaybackSearchRow("excluded-channels", stringResource(Res.string.settings_debrid_rule_excluded_channels), stringResource(Res.string.settings_debrid_rule_excluded_channels_desc), debridResultsSection),
        PlaybackSearchRow("preferred-encodes", stringResource(Res.string.settings_debrid_rule_preferred_encodes), stringResource(Res.string.settings_debrid_rule_preferred_encodes_desc), debridResultsSection),
        PlaybackSearchRow("required-encodes", stringResource(Res.string.settings_debrid_rule_required_encodes), stringResource(Res.string.settings_debrid_rule_required_encodes_desc), debridResultsSection),
        PlaybackSearchRow("excluded-encodes", stringResource(Res.string.settings_debrid_rule_excluded_encodes), stringResource(Res.string.settings_debrid_rule_excluded_encodes_desc), debridResultsSection),
        PlaybackSearchRow("preferred-languages", stringResource(Res.string.settings_debrid_rule_preferred_languages), stringResource(Res.string.settings_debrid_rule_preferred_languages_desc), debridResultsSection),
        PlaybackSearchRow("required-languages", stringResource(Res.string.settings_debrid_rule_required_languages), stringResource(Res.string.settings_debrid_rule_required_languages_desc), debridResultsSection),
        PlaybackSearchRow("excluded-languages", stringResource(Res.string.settings_debrid_rule_excluded_languages), stringResource(Res.string.settings_debrid_rule_excluded_languages_desc), debridResultsSection),
        PlaybackSearchRow("required-release-groups", stringResource(Res.string.settings_debrid_rule_required_release_groups), stringResource(Res.string.settings_debrid_rule_required_release_groups_desc), debridResultsSection),
        PlaybackSearchRow("excluded-release-groups", stringResource(Res.string.settings_debrid_rule_excluded_release_groups), stringResource(Res.string.settings_debrid_rule_excluded_release_groups_desc), debridResultsSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Debrid,
            key = "debrid-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = debridPage,
            section = row.sectionOverride ?: debridResultsSection,
        )
    }

    DebridProviders.visible().forEach { provider ->
        addRow(
            page = SettingsPage.Debrid,
            key = "debrid-provider-${provider.id}",
            title = provider.displayName,
            description = stringResource(
                if (provider.authMethod == com.nuvio.app.features.debrid.DebridProviderAuthMethod.DeviceCode) {
                    Res.string.settings_debrid_provider_device_description
                } else {
                    Res.string.settings_debrid_provider_description
                },
                provider.displayName,
            ),
            pageLabel = debridPage,
            section = debridMainSection,
        )
    }

    val liveTvNavigationSection = stringResource(Res.string.live_tv_settings_section_navigation)
    val liveTvProvidersSection = stringResource(Res.string.live_tv_settings_section_providers)
    val liveTvPlaylistSection = stringResource(Res.string.live_tv_settings_section_playlist)
    val liveTvEpgSection = stringResource(Res.string.live_tv_settings_section_epg)
    val liveTvReloadSection = stringResource(Res.string.live_tv_settings_section_reload)
    listOf(
        PlaybackSearchRow("navigation", stringResource(Res.string.live_tv_settings_navigation_title), stringResource(Res.string.live_tv_settings_navigation_description), liveTvNavigationSection),
        PlaybackSearchRow("playlist", stringResource(Res.string.live_tv_settings_playlist_label), stringResource(Res.string.live_tv_settings_description), liveTvPlaylistSection),
        PlaybackSearchRow("playlist-url", stringResource(Res.string.live_tv_settings_playlist_url_label), stringResource(Res.string.live_tv_settings_add_url_playlist), liveTvPlaylistSection),
        PlaybackSearchRow("local-playlist", stringResource(Res.string.live_tv_settings_add_local_playlist), stringResource(Res.string.live_tv_settings_playlist_source_local), liveTvPlaylistSection),
        PlaybackSearchRow("playlist-name", stringResource(Res.string.live_tv_settings_playlist_name_label), stringResource(Res.string.live_tv_settings_playlist_name_placeholder), liveTvPlaylistSection),
        PlaybackSearchRow("epg-auto", stringResource(Res.string.live_tv_settings_epg_auto_title), stringResource(Res.string.live_tv_settings_epg_auto_description), liveTvEpgSection),
        PlaybackSearchRow("epg-url", stringResource(Res.string.live_tv_settings_epg_label), stringResource(Res.string.live_tv_settings_epg_description), liveTvEpgSection),
        PlaybackSearchRow("epg-add", stringResource(Res.string.live_tv_settings_epg_add), stringResource(Res.string.live_tv_settings_epg_url_placeholder), liveTvEpgSection),
        PlaybackSearchRow("epg-refresh", stringResource(Res.string.live_tv_settings_epg_refresh), sectionOverride = liveTvEpgSection),
        PlaybackSearchRow("reload-channels", stringResource(Res.string.live_tv_settings_reload_channels), stringResource(Res.string.live_tv_settings_reload_description), liveTvReloadSection),
        PlaybackSearchRow("reload-epg", stringResource(Res.string.live_tv_settings_reload_epg), sectionOverride = liveTvReloadSection),
        PlaybackSearchRow("xtream-codes", stringResource(Res.string.live_tv_xtream_title), stringResource(Res.string.live_tv_xtream_description), liveTvProvidersSection),
        PlaybackSearchRow("stalker-portal", stringResource(Res.string.live_tv_stalker_title), stringResource(Res.string.live_tv_stalker_description), liveTvProvidersSection),
        PlaybackSearchRow("stalker-mac", stringResource(Res.string.live_tv_stalker_mac), stringResource(Res.string.live_tv_stalker_portal), liveTvProvidersSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LiveTv,
            key = "live-tv-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = liveTvPage,
            section = row.sectionOverride ?: liveTvProvidersSection,
        )
    }

    val discordAccountSection = stringResource(Res.string.discord_rpc_section_account)
    val discordPresenceSection = stringResource(Res.string.discord_rpc_section_presence)
    val discordDisplaySection = stringResource(Res.string.discord_rpc_section_display)
    val discordAdvancedSection = stringResource(Res.string.discord_rpc_section_advanced)
    listOf(
        PlaybackSearchRow("enable", stringResource(Res.string.discord_rpc_enable_title), stringResource(Res.string.discord_rpc_enable_description), discordPresenceSection),
        PlaybackSearchRow("activity-type", stringResource(Res.string.discord_rpc_activity_type_title), stringResource(Res.string.discord_rpc_activity_type_description), discordPresenceSection),
        PlaybackSearchRow("status", stringResource(Res.string.discord_rpc_status_title), stringResource(Res.string.discord_rpc_status_description), discordPresenceSection),
        PlaybackSearchRow("show-title-as-name", stringResource(Res.string.discord_rpc_show_title_as_name_title), stringResource(Res.string.discord_rpc_show_title_as_name_description), discordDisplaySection),
        PlaybackSearchRow("show-poster", stringResource(Res.string.discord_rpc_show_poster_title), stringResource(Res.string.discord_rpc_show_poster_description), discordDisplaySection),
        PlaybackSearchRow("show-playback-state", stringResource(Res.string.discord_rpc_show_playback_state_title), stringResource(Res.string.discord_rpc_show_playback_state_description), discordDisplaySection),
        PlaybackSearchRow("show-timestamps", stringResource(Res.string.discord_rpc_show_timestamps_title), stringResource(Res.string.discord_rpc_show_timestamps_description), discordDisplaySection),
        PlaybackSearchRow("hide-on-pause", stringResource(Res.string.discord_rpc_hide_on_pause_title), stringResource(Res.string.discord_rpc_hide_on_pause_description), discordDisplaySection),
        PlaybackSearchRow("show-imdb-button", stringResource(Res.string.discord_rpc_show_imdb_button_title), stringResource(Res.string.discord_rpc_show_imdb_button_description), discordDisplaySection),
        PlaybackSearchRow("application-id", stringResource(Res.string.discord_rpc_application_id_title), stringResource(Res.string.discord_rpc_application_id_description), discordAdvancedSection),
        PlaybackSearchRow("login", stringResource(Res.string.discord_rpc_login_button), stringResource(Res.string.discord_rpc_login_description), discordAccountSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.DiscordRpc,
            key = "discord-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = discordRpcPage,
            section = row.sectionOverride ?: discordAccountSection,
        )
    }

    val debugSection = stringResource(Res.string.settings_advanced_section_debugging)
    listOf(
        PlaybackSearchRow("copy-logs", stringResource(Res.string.settings_advanced_debugging_copy_logs), stringResource(Res.string.settings_advanced_debugging_copy_logs_description), debugSection),
        PlaybackSearchRow("clear-logs", stringResource(Res.string.settings_advanced_debugging_clear_logs), stringResource(Res.string.settings_advanced_debugging_clear_logs_description), debugSection),
        PlaybackSearchRow("log-viewer", stringResource(Res.string.settings_advanced_debugging_log_viewer_description), sectionOverride = debugSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.DebugLogs,
            key = "debug-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = debugLogsPage,
            section = row.sectionOverride ?: debugSection,
        )
    }

    addRow(
        page = SettingsPage.Account,
        key = "account-status",
        title = stringResource(Res.string.settings_account_status),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
    )
    addRow(
        page = SettingsPage.Account,
        key = "account-sign-out",
        title = stringResource(Res.string.settings_account_sign_out),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
    )
    if (accountDeletionEnabled && authState is AuthState.Authenticated) {
        addRow(
            page = SettingsPage.Account,
            key = "account-delete",
            title = stringResource(Res.string.settings_account_delete_account),
            description = stringResource(Res.string.settings_account_delete_account_description),
            pageLabel = accountPage,
            section = accountPage,
            category = accountCategory,
        )
    }

    addRow(
        page = SettingsPage.Appearance,
        key = "theme",
        title = stringResource(Res.string.settings_appearance_section_theme),
        pageLabel = appearancePage,
        section = stringResource(Res.string.settings_appearance_section_theme),
    )
    addRow(
        page = SettingsPage.Appearance,
        key = "amoled",
        title = stringResource(Res.string.settings_appearance_amoled_black),
        description = stringResource(Res.string.settings_appearance_amoled_description),
        pageLabel = appearancePage,
        section = stringResource(Res.string.settings_appearance_section_display),
    )
    addRow(
        page = SettingsPage.Layout,
        key = "dynamic-artwork-background",
        title = stringResource(Res.string.settings_appearance_dynamic_artwork_background),
        description = stringResource(Res.string.settings_appearance_dynamic_artwork_background_description),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_home),
    )
    addRow(
        page = SettingsPage.Layout,
        key = "catalog-accent",
        title = stringResource(Res.string.settings_appearance_catalog_accent),
        description = stringResource(Res.string.settings_appearance_catalog_accent_description),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_home),
    )
    if (liquidGlassNativeTabBarSupported) {
        addRow(
            page = SettingsPage.Layout,
            key = "liquid-glass",
            title = stringResource(Res.string.settings_appearance_tab_bar_behavior),
            description = stringResource(NuvioTabBarBehavior.Default.descriptionRes),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_nav_bar_style),
        )
    }
    addRow(
        page = SettingsPage.Appearance,
        key = "app-icon",
        title = stringResource(Res.string.settings_appearance_app_icon),
        pageLabel = appearancePage,
        section = stringResource(Res.string.settings_appearance_section_display),
    )
    if (!isIos && (!liquidGlassNativeTabBarSupported || !tabBarBehavior.isEnabled)) {
        addRow(
            page = SettingsPage.Layout,
            key = "navigation-bar",
            title = stringResource(Res.string.settings_appearance_nav_bar_style),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_nav_bar_style),
        )
    }
    addRow(
        page = SettingsPage.Appearance,
        key = "app-language",
        title = stringResource(Res.string.settings_appearance_app_language),
        pageLabel = appearancePage,
        section = stringResource(Res.string.settings_appearance_section_display),
    )
    addRow(
        page = SettingsPage.Advanced,
        key = "advanced-remember-last-profile",
        title = stringResource(Res.string.settings_advanced_remember_last_profile),
        description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
        pageLabel = advancedPage,
        section = stringResource(Res.string.settings_advanced_section_startup),
        category = advancedCategory,
    )
    if (DnsSettingsRepository.isSupported) {
        addRow(
            page = SettingsPage.Advanced,
            key = "advanced-dns-provider",
            title = stringResource(Res.string.settings_advanced_dns),
            description = stringResource(Res.string.settings_advanced_dns_note),
            pageLabel = advancedPage,
            section = stringResource(Res.string.settings_advanced_section_network),
            category = advancedCategory,
            searchTerms = listOf("dns over https", "doh", "cloudflare", "google dns", "system dns", "network resolver"),
        )
    }
    if (SentrySettingsRepository.isSupported) {
        addRow(
            page = SettingsPage.Advanced,
            key = "advanced-sentry-reports",
            title = stringResource(Res.string.settings_advanced_sentry_reports),
            description = stringResource(Res.string.settings_advanced_sentry_reports_subtitle),
            pageLabel = advancedPage,
            section = stringResource(Res.string.settings_advanced_section_diagnostics),
            category = advancedCategory,
        )
    }
    addPage(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching",
        title = continueWatchingPage,
        description = stringResource(Res.string.settings_appearance_continue_watching_description),
        searchTerms = listOf(
            "resume playback", "keep watching", "resume prompt",
            "continue playback", "next episode prompt",
        ),
    )
    addPage(
        page = SettingsPage.PosterCustomization,
        key = "poster-card-style",
        title = posterStylePage,
        description = stringResource(Res.string.settings_appearance_poster_customization_description),
        searchTerms = listOf(
            "poster design", "card appearance", "movie artwork cards",
            "tv artwork", "poster cards", "artwork style", "media cards",
        ),
    )

    addPage(
        page = SettingsPage.Addons,
        key = "addons",
        title = addonsPage,
        description = stringResource(
            if (personalMediaAddonCopyEnabled) {
                Res.string.settings_content_discovery_addons_description_appstore
            } else {
                Res.string.settings_content_discovery_addons_description
            },
        ),
    )
    addPage(
        page = SettingsPage.MediaServers,
        key = "media-servers",
        title = stringResource(Res.string.compose_settings_page_media_servers),
        description = stringResource(Res.string.settings_content_discovery_media_servers_description),
        searchTerms = listOf("plex", "jellyfin", "emby", "media server", "server connection", "network media"),
    )
    if (pluginsEnabled) {
        addPage(
            page = SettingsPage.Plugins,
            key = "plugins",
            title = pluginsPage,
            description = stringResource(Res.string.settings_content_discovery_plugins_description),
        )
    }
    addPage(
        page = SettingsPage.Homescreen,
        key = "home-layout",
        title = homeLayoutPage,
        description = stringResource(Res.string.settings_content_discovery_homescreen_description),
        searchTerms = listOf(
            "home screen", "homepage", "home page", "hero", "featured content",
            "trailers", "catalog rows", "home screen layout", "home page settings",
        ),
    )
    addPage(
        page = SettingsPage.MetaScreen,
        key = "detail-page",
        title = detailPage,
        description = stringResource(Res.string.settings_content_discovery_meta_screen_description),
        searchTerms = listOf(
            "details screen", "detail page", "movie details", "tv show details",
            "metadata", "movie page", "show page", "cast", "synopsis", "backdrop",
        ),
    )
    add(
        key = "collections",
        title = collectionsPage,
        description = stringResource(Res.string.settings_content_discovery_collections_description),
        page = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_home),
        category = generalCategory,
        target = SettingsSearchTarget.Collections,
    )

    val playbackPlayer = stringResource(Res.string.settings_playback_section_player)
    val playbackSubtitleAudio = stringResource(Res.string.settings_playback_section_subtitle_audio)
    val playbackStreamSelection = stringResource(Res.string.settings_playback_section_stream_selection)
    val playbackStreamAutoPlay = stringResource(Res.string.settings_playback_section_stream_auto_play)
    val playbackDecoder = stringResource(Res.string.settings_playback_section_decoder)
    val playbackSubtitleRendering = stringResource(Res.string.settings_playback_section_subtitle_rendering)
    val playbackSkipSegments = stringResource(Res.string.settings_playback_section_skip_segments)
    val playbackNextEpisode = stringResource(Res.string.settings_playback_section_next_episode)
    addRow(
        page = SettingsPage.Streams,
        key = "stream-addon-logo",
        title = stringResource(Res.string.settings_stream_addon_logo_title),
        description = stringResource(Res.string.settings_stream_addon_logo_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_display_section),
    )
    if (!isTablet) {
        addRow(
            page = SettingsPage.Streams,
            key = "stream-background",
            title = stringResource(Res.string.settings_stream_background_title),
            description = stringResource(Res.string.settings_stream_background_description),
            pageLabel = streamsPage,
            section = stringResource(Res.string.settings_stream_display_section),
        )
    }
    addRow(
        page = SettingsPage.Streams,
        key = "stream-size-badges",
        title = stringResource(Res.string.settings_stream_size_badges_title),
        description = stringResource(Res.string.settings_stream_size_badges_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-position",
        title = stringResource(Res.string.settings_stream_badge_position_title),
        description = stringResource(Res.string.settings_stream_badge_position_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-urls",
        title = stringResource(Res.string.settings_stream_badge_urls_title),
        description = stringResource(Res.string.settings_stream_badge_urls_search_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackPlayer,
        page = SettingsPage.PlaybackInterface,
        rows = listOfNotNull(
            PlaybackSearchRow(
                "legacy-player-layout",
                stringResource(Res.string.settings_playback_legacy_layout),
                stringResource(Res.string.settings_playback_legacy_layout_description),
            ),
            PlaybackSearchRow(
                "player-title-logo",
                stringResource(Res.string.settings_playback_show_title_logo),
                stringResource(Res.string.settings_playback_show_title_logo_description),
            ),
            PlaybackSearchRow(
                "player-control-glass",
                stringResource(Res.string.settings_playback_control_glass),
                stringResource(Res.string.settings_playback_control_glass_description),
            ),
            PlaybackSearchRow(
                "player-skip-buttons",
                stringResource(Res.string.settings_playback_show_skip_buttons),
                stringResource(Res.string.settings_playback_show_skip_buttons_description),
            ),
            PlaybackSearchRow(
                "loading-overlay",
                stringResource(Res.string.settings_playback_show_loading_overlay),
                stringResource(Res.string.settings_playback_show_loading_overlay_description),
            ),
            PlaybackSearchRow(
                "pause-overlay",
                stringResource(Res.string.settings_playback_pause_overlay),
                stringResource(Res.string.settings_playback_pause_overlay_description),
            ),
            PlaybackSearchRow(
                "external-player",
                stringResource(Res.string.settings_playback_external_player),
                stringResource(Res.string.settings_playback_external_player_description_android),
                page = SettingsPage.PlaybackPlayer,
            ),
            if (isIos) PlaybackSearchRow(
                "external-player-app",
                stringResource(Res.string.settings_playback_external_player_app),
                page = SettingsPage.PlaybackPlayer,
            ) else null,
            PlaybackSearchRow(
                "hold-to-speed",
                stringResource(Res.string.settings_playback_hold_to_speed),
                stringResource(Res.string.settings_playback_hold_to_speed_description),
            ),
            PlaybackSearchRow(
                "seek-preview",
                stringResource(Res.string.settings_playback_seek_preview),
                stringResource(Res.string.settings_playback_seek_preview_description),
            ),
            PlaybackSearchRow(
                "touch-gestures",
                stringResource(Res.string.settings_playback_touch_gestures),
                stringResource(Res.string.settings_playback_touch_gestures_description),
            ),
            PlaybackSearchRow(
                "swipe-to-seek",
                stringResource(Res.string.settings_playback_swipe_to_seek),
                stringResource(Res.string.settings_playback_swipe_to_seek_description),
            ),
            PlaybackSearchRow(
                "movie-recommendations",
                stringResource(Res.string.settings_playback_movie_recommendations),
                stringResource(Res.string.settings_playback_movie_recommendations_description),
            ),
            PlaybackSearchRow("hold-speed", stringResource(Res.string.settings_playback_hold_speed)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSubtitleAudio,
        page = SettingsPage.PlaybackAudio,
        rows = buildList {
            add(PlaybackSearchRow("audio-preferred-language", stringResource(Res.string.settings_playback_preferred_audio_language), page = SettingsPage.PlaybackAudio))
            add(PlaybackSearchRow("audio-secondary-language", stringResource(Res.string.settings_playback_secondary_audio_language), page = SettingsPage.PlaybackAudio))
            add(PlaybackSearchRow("audio-night-mode-button", stringResource(Res.string.settings_playback_night_mode_button), stringResource(Res.string.settings_playback_night_mode_button_description), page = SettingsPage.PlaybackAudio))
            if (!isIos) {
                add(PlaybackSearchRow("audio-remember-delay", stringResource(Res.string.settings_playback_remember_audio_delay), stringResource(Res.string.settings_playback_remember_audio_delay_description), page = SettingsPage.PlaybackAudio))
                add(PlaybackSearchRow("audio-downmix", stringResource(Res.string.settings_playback_downmix), stringResource(Res.string.settings_playback_downmix_description), page = SettingsPage.PlaybackAudio))
                if (playerSettings.effectiveDownmixEnabled) {
                    add(PlaybackSearchRow("audio-downmix-channels", stringResource(Res.string.settings_playback_downmix_channels), page = SettingsPage.PlaybackAudio))
                    add(PlaybackSearchRow("audio-downmix-maintain-volume", stringResource(Res.string.settings_playback_downmix_maintain_volume), stringResource(Res.string.settings_playback_downmix_maintain_volume_description), page = SettingsPage.PlaybackAudio))
                }
            } else {
                add(PlaybackSearchRow("audio-ios-output", stringResource(Res.string.settings_playback_ios_audio_output), page = SettingsPage.PlaybackAudio))
            }
        },
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSubtitleAudio,
        page = SettingsPage.PlaybackSubtitles,
        rows = listOf(
            PlaybackSearchRow("preferred-subtitles", stringResource(Res.string.settings_playback_preferred_subtitle_language)),
            PlaybackSearchRow("secondary-subtitles", stringResource(Res.string.settings_playback_secondary_subtitle_language)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamSelection,
        page = SettingsPage.PlaybackStreamSelection,
        rows = listOf(
            PlaybackSearchRow(
                "reuse-last-link",
                stringResource(Res.string.settings_playback_reuse_last_link),
                stringResource(Res.string.settings_playback_reuse_last_link_description),
            ),
            PlaybackSearchRow("last-link-cache", stringResource(Res.string.settings_playback_last_link_cache_duration)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamAutoPlay,
        page = SettingsPage.PlaybackStreamSelection,
        rows = buildList {
            add(PlaybackSearchRow("stream-mode", stringResource(Res.string.settings_playback_stream_selection_mode)))
            add(PlaybackSearchRow("regex-pattern", stringResource(Res.string.settings_playback_regex_pattern)))
            add(PlaybackSearchRow("stream-timeout", stringResource(Res.string.settings_playback_stream_timeout), stringResource(Res.string.settings_playback_stream_timeout_description)))
            add(PlaybackSearchRow("source-scope", stringResource(Res.string.settings_playback_source_scope)))
            add(PlaybackSearchRow("allowed-addons", stringResource(Res.string.settings_playback_allowed_addons)))
            if (pluginsEnabled) add(PlaybackSearchRow("allowed-plugins", stringResource(Res.string.settings_playback_allowed_plugins)))
        },
    )
    if (!isIos) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackDecoder,
            page = SettingsPage.PlaybackVideo,
            rows = buildList {
                add(PlaybackSearchRow("auto-switch-engine", stringResource(Res.string.settings_playback_auto_switch_engine_on_error), stringResource(Res.string.settings_playback_auto_switch_engine_on_error_description), page = SettingsPage.PlaybackPlayer))
                if (playerSettings.androidPlaybackEngine != com.nuvio.app.features.player.AndroidPlaybackEngine.ExoPlayer) {
                    add(PlaybackSearchRow("libmpv-video-output", stringResource(Res.string.settings_playback_libmpv_video_output)))
                    add(PlaybackSearchRow("libmpv-hardware-decoding", stringResource(Res.string.settings_playback_libmpv_hardware_decoding), stringResource(Res.string.settings_playback_libmpv_hardware_decoding_description)))
                    add(PlaybackSearchRow("libmpv-yuv420p", stringResource(Res.string.settings_playback_libmpv_yuv420p), stringResource(Res.string.settings_playback_libmpv_yuv420p_description)))
                }
                add(PlaybackSearchRow("decoder-priority", stringResource(Res.string.settings_playback_decoder_priority)))
                add(PlaybackSearchRow("dv7-hevc", stringResource(Res.string.settings_playback_map_dv7_to_hevc), stringResource(Res.string.settings_playback_map_dv7_to_hevc_description)))
                add(PlaybackSearchRow("tunneled-playback", stringResource(Res.string.settings_playback_tunneled_playback), stringResource(Res.string.settings_playback_tunneled_playback_description)))
                add(PlaybackSearchRow("audio-passthrough", stringResource(Res.string.settings_playback_audio_passthrough), stringResource(Res.string.settings_playback_audio_passthrough_description)))
                add(PlaybackSearchRow("anime-upscale", stringResource(Res.string.settings_playback_anime_upscale), stringResource(Res.string.settings_playback_anime_upscale_description), sectionOverride = stringResource(Res.string.settings_playback_section_anime_enhancement)))
                add(PlaybackSearchRow("anime-line-sharpen", stringResource(Res.string.settings_playback_anime_line_sharpen), stringResource(Res.string.settings_playback_anime_line_sharpen_description), sectionOverride = stringResource(Res.string.settings_playback_section_anime_enhancement)))
                add(PlaybackSearchRow("exo-native-memory", stringResource(Res.string.settings_playback_exo_native_memory), stringResource(Res.string.settings_playback_exo_native_memory_description), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer))
                add(PlaybackSearchRow("custom-buffers", stringResource(Res.string.settings_playback_custom_buffers), stringResource(Res.string.settings_playback_custom_buffers_description), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer))
                if (playerSettings.customPlaybackBuffersEnabled) {
                    add(PlaybackSearchRow("min-buffer", stringResource(Res.string.settings_playback_min_buffer), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("buffer duration", "minimum buffering")))
                    add(PlaybackSearchRow("max-buffer", stringResource(Res.string.settings_playback_max_buffer), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("buffer duration", "maximum buffering")))
                    add(PlaybackSearchRow("start-buffer", stringResource(Res.string.settings_playback_start_buffer), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("startup buffering", "initial buffer")))
                    add(PlaybackSearchRow("buffer-after-stall", stringResource(Res.string.settings_playback_buffer_after_stall), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("rebuffer duration", "buffer after interruption")))
                    add(PlaybackSearchRow("back-buffer", stringResource(Res.string.settings_playback_back_buffer), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("back buffer", "rewind buffer")))
                    add(PlaybackSearchRow("target-buffer-size", stringResource(Res.string.settings_playback_target_buffer_size), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("buffer memory", "buffer size megabytes")))
                }
                add(PlaybackSearchRow("vod-cache", stringResource(Res.string.settings_playback_vod_disk_cache), stringResource(Res.string.settings_playback_vod_disk_cache_description), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer))
                if (playerSettings.vodDiskCacheEnabled) {
                    add(PlaybackSearchRow("vod-cache-auto-size", stringResource(Res.string.settings_playback_vod_disk_cache_auto_size), stringResource(Res.string.settings_playback_vod_disk_cache_auto_size_description), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("automatic cache size", "disk cache sizing")))
                    if (!playerSettings.vodDiskCacheAutoSize) {
                        add(PlaybackSearchRow("vod-cache-size", stringResource(Res.string.settings_playback_vod_disk_cache_size), sectionOverride = stringResource(Res.string.settings_playback_page_buffer), page = SettingsPage.PlaybackBuffer, searchTerms = listOf("cache size megabytes", "disk storage limit")))
                    }
                }
                add(PlaybackSearchRow("exo-chapters", stringResource(Res.string.settings_playback_exo_chapters), stringResource(Res.string.settings_playback_exo_chapters_description), page = SettingsPage.PlaybackInterface))
            },
        )
    }
    if (P2pSettingsRepository.isVisible) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = stringResource(Res.string.settings_playback_section_p2p),
            page = SettingsPage.PlaybackP2p,
            rows = listOf(
                PlaybackSearchRow("p2p-enabled", stringResource(Res.string.settings_p2p_title), stringResource(Res.string.settings_p2p_subtitle)),
                PlaybackSearchRow("p2p-hide-stats", stringResource(Res.string.settings_p2p_hide_stats_title), stringResource(Res.string.settings_p2p_hide_stats_subtitle)),
                PlaybackSearchRow("p2p-profile", stringResource(Res.string.settings_p2p_profile_title)),
                PlaybackSearchRow("p2p-cache-size", stringResource(Res.string.settings_p2p_cache_size_title)),
                PlaybackSearchRow("p2p-clear-cache", stringResource(Res.string.settings_p2p_clear_cache_title)),
            ),
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSubtitleRendering,
        page = SettingsPage.PlaybackSubtitles,
        rows = listOf(
            PlaybackSearchRow("subtitle-font", stringResource(Res.string.subtitle_font), stringResource(Res.string.subtitle_font_import_hint)),
            PlaybackSearchRow("subtitle-background-opacity", stringResource(Res.string.subtitle_background_opacity)),
            PlaybackSearchRow("subtitle-background-roundness", stringResource(Res.string.subtitle_background_roundness)),
            PlaybackSearchRow("subtitle-shadow", stringResource(Res.string.subtitle_shadow), stringResource(Res.string.subtitle_shadow_description)),
        ),
    )
    if (!isIos && playerSettings.androidPlaybackEngine != com.nuvio.app.features.player.AndroidPlaybackEngine.Libmpv) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackSubtitleRendering,
            page = SettingsPage.PlaybackSubtitles,
            rows = buildList {
                add(PlaybackSearchRow("libass", stringResource(Res.string.settings_playback_enable_libass), stringResource(Res.string.settings_playback_enable_libass_description)))
                if (playerSettings.useLibass) {
                    add(PlaybackSearchRow("libass-render-mode", stringResource(Res.string.settings_playback_render_mode), stringResource(Res.string.settings_playback_render_mode_description)))
                    add(PlaybackSearchRow("libass-render", stringResource(Res.string.settings_playback_render_scale)))
                }
                add(PlaybackSearchRow("libmpv-ass", stringResource(Res.string.settings_playback_enable_libmpv_ass), stringResource(Res.string.settings_playback_enable_libmpv_ass_description)))
            },
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSkipSegments,
        page = SettingsPage.PlaybackSkipSegments,
        rows = listOf(
            PlaybackSearchRow("skip-intro", stringResource(Res.string.settings_playback_skip_intro_outro_recap), stringResource(Res.string.settings_playback_skip_intro_outro_recap_description)),
            PlaybackSearchRow("anime-skip", stringResource(Res.string.settings_playback_anime_skip), stringResource(Res.string.settings_playback_anime_skip_description)),
            PlaybackSearchRow("anime-skip-client", stringResource(Res.string.settings_playback_anime_skip_client_id), stringResource(Res.string.settings_playback_anime_skip_client_id_description)),
            PlaybackSearchRow("intro-submit", stringResource(Res.string.settings_playback_intro_submit_enabled), stringResource(Res.string.settings_playback_intro_submit_enabled_description)),
            PlaybackSearchRow("introdb-key", stringResource(Res.string.settings_playback_introdb_api_key), stringResource(Res.string.settings_playback_introdb_api_key_description)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackNextEpisode,
        page = SettingsPage.PlaybackUpNext,
        rows = listOf(
            PlaybackSearchRow("auto-play-next", stringResource(Res.string.settings_playback_auto_play_next_episode), stringResource(Res.string.settings_playback_auto_play_next_episode_description)),
            PlaybackSearchRow("prefer-binge", stringResource(Res.string.settings_playback_prefer_binge_group), stringResource(Res.string.settings_playback_prefer_binge_group_description)),
            PlaybackSearchRow(
                "threshold-mode",
                stringResource(Res.string.settings_playback_threshold_mode),
                searchTerms = listOf(
                    "threshold percentage", "percentage before end", "minutes before end",
                    "when to show next episode", "next episode countdown",
                ),
            ),
        ),
    )

    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_visibility),
        rows = listOf(
            PlaybackSearchRow(
                "show-continue-watching",
                stringResource(Res.string.settings_continue_watching_show_title),
                stringResource(Res.string.settings_continue_watching_show_description),
            ),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_card_style),
        rows = listOf(PlaybackSearchRow("style", stringResource(Res.string.settings_continue_watching_section_card_style), searchTerms = listOf("continue watching card style", "poster style", "wide cards"))),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_up_next_behavior),
        rows = buildList {
            if (continueWatchingStyle != ContinueWatchingSectionStyle.Poster) add(PlaybackSearchRow("episode-thumbnails", stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title), stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_description)))
            add(PlaybackSearchRow("up-next", stringResource(Res.string.settings_continue_watching_up_next_title), stringResource(Res.string.settings_continue_watching_up_next_description)))
            add(PlaybackSearchRow("unaired-next-up", stringResource(Res.string.settings_continue_watching_show_unaired_next_up_title), stringResource(Res.string.settings_continue_watching_show_unaired_next_up_description)))
            if (continueWatchingStyle != ContinueWatchingSectionStyle.Poster && continueWatchingEpisodeThumbnails) add(PlaybackSearchRow("blur-next-up", stringResource(Res.string.settings_continue_watching_blur_next_up_title), stringResource(Res.string.settings_continue_watching_blur_next_up_description)))
        },
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_sort_order),
        rows = listOf(PlaybackSearchRow("sort-mode", stringResource(Res.string.settings_continue_watching_sort_mode_title), searchTerms = listOf("continue watching order", "sort by release date", "streaming order"))),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_on_launch),
        rows = listOf(
            PlaybackSearchRow("resume-prompt", stringResource(Res.string.settings_continue_watching_resume_prompt_title), stringResource(Res.string.settings_continue_watching_resume_prompt_description)),
        ),
    )

    val posterSection = stringResource(Res.string.settings_poster_card_style)
    listOf(
        PlaybackSearchRow("poster-width", stringResource(Res.string.settings_poster_card_width)),
        PlaybackSearchRow("poster-radius", stringResource(Res.string.settings_poster_card_radius)),
        PlaybackSearchRow("poster-landscape", stringResource(Res.string.settings_poster_landscape_mode)),
        PlaybackSearchRow("poster-hide-labels", stringResource(Res.string.settings_poster_hide_labels)),
        PlaybackSearchRow("poster-always-show-landscape-clearlogo", stringResource(Res.string.settings_poster_always_show_landscape_clearlogo)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.PosterCustomization,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = posterStylePage,
            section = posterSection,
        )
    }

    val cardDepthSection = stringResource(Res.string.settings_card_depth_title)
    buildList {
        add(PlaybackSearchRow("card-depth-effect", cardDepthSection, stringResource(Res.string.settings_card_depth_description)))
        if (cardDepthStyleUiState.enabled) {
            add(PlaybackSearchRow("card-depth-edge", stringResource(Res.string.settings_card_depth_edge)))
            add(PlaybackSearchRow("card-depth-sheen", stringResource(Res.string.settings_card_depth_sheen)))
            add(PlaybackSearchRow("card-depth-edge-coverage", stringResource(Res.string.settings_card_depth_edge_coverage)))
            add(PlaybackSearchRow("card-depth-fine-tune", stringResource(Res.string.settings_card_depth_fine_tune)))
            add(PlaybackSearchRow("card-depth-surface-posters", stringResource(Res.string.settings_card_depth_surface_posters)))
            add(PlaybackSearchRow("card-depth-surface-continue-watching", stringResource(Res.string.settings_card_depth_surface_continue_watching)))
            add(PlaybackSearchRow("card-depth-surface-episode-cards", stringResource(Res.string.settings_card_depth_surface_episodes)))
            add(PlaybackSearchRow("card-depth-surface-cast", stringResource(Res.string.settings_card_depth_surface_cast)))
            add(PlaybackSearchRow("card-depth-surface-trailers", stringResource(Res.string.settings_card_depth_surface_trailers)))
        }
    }.forEach { row ->
        addRow(
            page = SettingsPage.PosterCustomization,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = posterStylePage,
            section = cardDepthSection,
        )
    }

    val homeLayoutSection = stringResource(Res.string.settings_homescreen_section_hero)
    listOf(
        PlaybackSearchRow("home-hero", stringResource(Res.string.settings_homescreen_show_hero), stringResource(Res.string.settings_homescreen_show_hero_description)),
        PlaybackSearchRow("home-catalog-type", stringResource(Res.string.layout_catalog_type), stringResource(Res.string.layout_catalog_type_sub)),
        PlaybackSearchRow("home-hide-unreleased", stringResource(Res.string.layout_hide_unreleased), stringResource(Res.string.layout_hide_unreleased_sub)),
        PlaybackSearchRow("home-hero-sources", stringResource(Res.string.settings_homescreen_section_hero_sources)),
        PlaybackSearchRow("home-catalogs", stringResource(Res.string.settings_homescreen_section_catalogs)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Homescreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = homeLayoutPage,
            section = homeLayoutSection,
        )
    }

    val detailAppearanceSection = stringResource(Res.string.settings_meta_section_appearance)
    if (supportsPosterNavigationMotion) {
        addRow(
            page = SettingsPage.MetaScreen,
            key = "meta-poster-transition",
            title = stringResource(Res.string.settings_meta_poster_transition),
            description = stringResource(Res.string.settings_meta_poster_transition_description),
            pageLabel = detailPage,
            section = detailAppearanceSection,
        )
    }
    listOf(
        PlaybackSearchRow("meta-background-mode", stringResource(Res.string.settings_meta_background_mode), stringResource(Res.string.settings_meta_background_mode_description)),
        PlaybackSearchRow("meta-tabs", stringResource(Res.string.settings_meta_tab_layout), stringResource(Res.string.settings_meta_tab_layout_description)),
        PlaybackSearchRow(
            "meta-overall-ratings",
            stringResource(Res.string.layout_overall_ratings),
            stringResource(Res.string.layout_overall_ratings_sub_on) + " " +
                stringResource(Res.string.layout_overall_ratings_sub_off),
        ),
        PlaybackSearchRow(
            "meta-episode-ratings",
            stringResource(Res.string.layout_episode_ratings),
            listOf(
                stringResource(Res.string.layout_episode_ratings_sub),
                stringResource(Res.string.layout_ratings_show),
                stringResource(Res.string.layout_ratings_hide),
                stringResource(Res.string.layout_ratings_hide_unwatched),
            ).joinToString(" "),
        ),
        PlaybackSearchRow("meta-episode-cards", stringResource(Res.string.settings_meta_episode_cards), stringResource(Res.string.settings_meta_episode_cards_description)),
        PlaybackSearchRow("meta-shuffle", stringResource(Res.string.random_episode_title), stringResource(Res.string.layout_random_episode_sub)),
        PlaybackSearchRow("meta-blur-episodes", stringResource(Res.string.settings_meta_blur_unwatched_episodes), stringResource(Res.string.settings_meta_blur_unwatched_episodes_description)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MetaScreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = detailPage,
            section = detailAppearanceSection,
        )
    }
    val detailSectionsSection = stringResource(Res.string.settings_meta_section_sections)
    listOf(
        PlaybackSearchRow("meta-overview", stringResource(Res.string.settings_meta_overview), stringResource(Res.string.settings_meta_overview_description)),
        PlaybackSearchRow("meta-actions", stringResource(Res.string.settings_meta_actions), stringResource(Res.string.settings_meta_actions_description)),
        PlaybackSearchRow("meta-details", stringResource(Res.string.settings_meta_details), stringResource(Res.string.settings_meta_details_description)),
        PlaybackSearchRow("meta-trailers", stringResource(Res.string.settings_meta_trailers), stringResource(Res.string.settings_meta_trailers_description)),
        PlaybackSearchRow("meta-cast", stringResource(Res.string.settings_meta_cast), stringResource(Res.string.settings_meta_cast_description)),
        PlaybackSearchRow("meta-episodes", stringResource(Res.string.settings_meta_episodes), stringResource(Res.string.settings_meta_episodes_description)),
        PlaybackSearchRow("meta-production", stringResource(Res.string.settings_meta_production), stringResource(Res.string.settings_meta_production_description)),
        PlaybackSearchRow("meta-more-like-this", stringResource(Res.string.settings_meta_more_like_this), stringResource(Res.string.settings_meta_more_like_this_description)),
        PlaybackSearchRow("meta-collection", stringResource(Res.string.settings_meta_collection), stringResource(Res.string.settings_meta_collection_description)),
        PlaybackSearchRow("meta-comments", stringResource(Res.string.settings_meta_comments), stringResource(Res.string.settings_meta_comments_description)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MetaScreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = detailPage,
            section = detailSectionsSection,
        )
    }

    addPage(
        page = SettingsPage.TmdbEnrichment,
        key = "tmdb",
        title = tmdbPage,
        description = stringResource(Res.string.settings_integrations_tmdb_description),
    )
    addPage(
        page = SettingsPage.MdbListRatings,
        key = "mdblist",
        title = mdbListPage,
        description = stringResource(Res.string.settings_integrations_mdblist_description),
    )
    addPage(
        page = SettingsPage.Omdb,
        key = "omdb",
        title = omdbPage,
        description = stringResource(Res.string.settings_integrations_omdb_description),
    )
    val tmdbModulesSection = stringResource(Res.string.settings_tmdb_section_modules)
    listOf(
        PlaybackSearchRow("tmdb-enable", stringResource(Res.string.settings_tmdb_enable_enrichment), stringResource(Res.string.settings_tmdb_enable_enrichment_description), stringResource(Res.string.settings_tmdb_section_title)),
        PlaybackSearchRow("tmdb-api-key", stringResource(Res.string.settings_tmdb_personal_api_key), stringResource(Res.string.settings_tmdb_api_key_override_description), stringResource(Res.string.settings_tmdb_section_title)),
        PlaybackSearchRow("tmdb-language", stringResource(Res.string.settings_tmdb_preferred_language), stringResource(Res.string.settings_tmdb_preferred_language_description), stringResource(Res.string.settings_tmdb_section_localization)),
        PlaybackSearchRow("tmdb-trailers", stringResource(Res.string.settings_tmdb_module_trailers), stringResource(Res.string.settings_tmdb_module_trailers_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-artwork", stringResource(Res.string.settings_tmdb_module_artwork), stringResource(Res.string.settings_tmdb_module_artwork_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-basic-info", stringResource(Res.string.settings_tmdb_module_basic_info), stringResource(Res.string.settings_tmdb_module_basic_info_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-details", stringResource(Res.string.settings_tmdb_module_details), stringResource(Res.string.settings_tmdb_module_details_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-credits", stringResource(Res.string.settings_tmdb_module_credits), stringResource(Res.string.settings_tmdb_module_credits_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-companies", stringResource(Res.string.settings_tmdb_module_production_companies), stringResource(Res.string.settings_tmdb_module_production_companies_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-networks", stringResource(Res.string.settings_tmdb_module_networks), stringResource(Res.string.settings_tmdb_module_networks_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-episodes", stringResource(Res.string.settings_tmdb_module_episodes), stringResource(Res.string.settings_tmdb_module_episodes_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-season-posters", stringResource(Res.string.settings_tmdb_module_season_posters), stringResource(Res.string.settings_tmdb_module_season_posters_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-more-like-this", stringResource(Res.string.settings_tmdb_module_more_like_this), stringResource(Res.string.settings_tmdb_module_more_like_this_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-collections", stringResource(Res.string.settings_tmdb_module_collections), stringResource(Res.string.settings_tmdb_module_collections_description), tmdbModulesSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TmdbEnrichment,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = tmdbPage,
            section = row.sectionOverride ?: tmdbModulesSection,
        )
    }

    listOf(
        PlaybackSearchRow("mdb-enable", stringResource(Res.string.settings_mdb_enable_ratings), stringResource(Res.string.settings_mdb_enable_ratings_description), stringResource(Res.string.settings_mdb_section_title)),
        PlaybackSearchRow("mdb-show-on-hero", stringResource(Res.string.settings_mdb_show_on_hero), stringResource(Res.string.settings_mdb_show_on_hero_description), stringResource(Res.string.settings_mdb_section_title)),
        PlaybackSearchRow("mdb-api-key", stringResource(Res.string.settings_mdb_api_key_title), stringResource(Res.string.settings_mdb_api_key_description), stringResource(Res.string.settings_mdb_section_api_key)),
        PlaybackSearchRow("mdb-imdb", stringResource(Res.string.source_imdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tmdb", stringResource(Res.string.source_tmdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tomatoes", stringResource(Res.string.source_rotten_tomatoes), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-metacritic", stringResource(Res.string.source_metacritic), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-trakt", stringResource(Res.string.source_trakt), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-letterboxd", stringResource(Res.string.source_letterboxd), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-audience", stringResource(Res.string.source_audience_score), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MdbListRatings,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = mdbListPage,
            section = row.sectionOverride ?: stringResource(Res.string.settings_mdb_section_title),
        )
    }

    listOf(
        PlaybackSearchRow("omdb-api-key", stringResource(Res.string.settings_omdb_personal_api_key), stringResource(Res.string.settings_omdb_enter_api_key), stringResource(Res.string.settings_omdb_section_title)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Omdb,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = omdbPage,
            section = row.sectionOverride ?: stringResource(Res.string.settings_omdb_section_title),
        )
    }

    val notificationsAlerts = stringResource(Res.string.settings_notifications_section_alerts)
    addRow(
        page = SettingsPage.Notifications,
        key = "episode-release-alerts",
        title = stringResource(Res.string.settings_notifications_episode_release_alerts),
        description = stringResource(Res.string.settings_notifications_episode_release_alerts_description),
        pageLabel = notificationsPage,
        section = notificationsAlerts,
    )
    addRow(
        page = SettingsPage.Notifications,
        key = "notification-test",
        title = stringResource(Res.string.settings_notifications_test_title),
        pageLabel = notificationsPage,
        section = stringResource(Res.string.settings_notifications_section_test),
    )

    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "private-viewing",
        title = stringResource(Res.string.settings_playback_private_viewing),
        description = stringResource(Res.string.settings_playback_private_viewing_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_privacy_section),
    )
    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "trakt-authentication",
        title = stringResource(Res.string.trakt_library_source_trakt),
        description = stringResource(Res.string.settings_trakt_intro_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
    )
    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "simkl-authentication",
        title = stringResource(Res.string.tracking_source_simkl),
        description = stringResource(Res.string.settings_simkl_sign_in_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
    )
    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "mdblist-authentication",
        title = stringResource(Res.string.tracking_source_mdblist),
        description = stringResource(Res.string.settings_mdblist_sign_in_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
    )
    listOf(
        PlaybackSearchRow("trakt-library-source", stringResource(Res.string.trakt_library_source_title), stringResource(Res.string.trakt_library_source_subtitle)),
        PlaybackSearchRow("trakt-watch-progress", stringResource(Res.string.trakt_watch_progress_title), stringResource(Res.string.trakt_watch_progress_subtitle)),
        PlaybackSearchRow("trakt-continue-watching-window", stringResource(Res.string.trakt_continue_watching_window), stringResource(Res.string.trakt_continue_watching_subtitle)),
        PlaybackSearchRow("trakt-comments", stringResource(Res.string.settings_trakt_comments), stringResource(Res.string.settings_trakt_comments_description)),
        PlaybackSearchRow("trakt-more-like-this-source", stringResource(Res.string.trakt_more_like_this_source_title), stringResource(Res.string.trakt_more_like_this_source_subtitle)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TraktAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = trackingPage,
            section = stringResource(Res.string.settings_tracking_features),
            category = accountCategory,
        )
    }

    return deduplicateSettingsSearchEntries(entries)
}

private data class SettingsSearchEntryIdentity(
    val destination: String,
    val page: String,
    val section: String,
    val title: String,
    val description: String,
)

/** Collapses repeated catalogue records for the same visible setting without losing aliases. */
internal fun deduplicateSettingsSearchEntries(
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val uniqueEntries = linkedMapOf<SettingsSearchEntryIdentity, SettingsSearchEntry>()
    entries.forEach { entry ->
        val destination = when (val target = entry.target) {
            is SettingsSearchTarget.Page -> "page:${target.page.name}"
            SettingsSearchTarget.Collections -> "collections"
            SettingsSearchTarget.SwitchProfile -> "switch-profile"
            SettingsSearchTarget.CheckForUpdates -> "check-for-updates"
        }
        val identity = SettingsSearchEntryIdentity(
            destination = destination,
            page = normalizeSettingsSearchText(entry.page),
            section = normalizeSettingsSearchText(entry.section),
            title = normalizeSettingsSearchText(entry.title),
            description = normalizeSettingsSearchText(entry.description),
        )
        val existing = uniqueEntries[identity]
        if (existing == null) {
            uniqueEntries[identity] = entry
        } else {
            val preferred = when {
                existing.target is SettingsSearchTarget.Page && existing.target.anchor == null &&
                    entry.target is SettingsSearchTarget.Page && entry.target.anchor != null -> entry
                else -> existing
            }
            uniqueEntries[identity] = preferred.copy(
                searchTerms = (existing.searchTerms + entry.searchTerms).distinct(),
            )
        }
    }
    return uniqueEntries.values.toList()
}

private data class PlaybackSearchRow(
    val key: String,
    val title: String,
    val description: String = "",
    val sectionOverride: String? = null,
    val page: SettingsPage? = null,
)

private fun addPlaybackRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
    ) -> Unit,
    page: SettingsPage,
    pageLabel: String,
    section: String,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            row.page ?: page,
            "playback-${row.key}",
            row.title,
            row.description,
            pageLabel,
            row.sectionOverride ?: section,
            "",
        )
    }
}

private fun addContinueWatchingRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
    ) -> Unit,
    pageLabel: String,
    section: String,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            SettingsPage.ContinueWatching,
            "continue-watching-${row.key}",
            row.title,
            row.description,
            pageLabel,
            section,
            "",
        )
    }
}

internal fun LazyListScope.settingsSearchRootContent(
    query: String,
    entries: @Composable () -> List<SettingsSearchEntry>,
    isTablet: Boolean,
    showSearchField: Boolean,
    animateSearchField: Boolean,
    onQueryChange: (String) -> Unit,
    onTargetClick: (SettingsSearchTarget) -> Unit,
) {
    if (showSearchField || query.isNotBlank()) {
        item(key = "settings-search-field") {
            SettingsSearchRevealItem(animate = animateSearchField) {
                SettingsSearchField(
                    query = query,
                    isTablet = isTablet,
                    onQueryChange = onQueryChange,
                )
            }
        }
    }

    if (query.isBlank()) return

    item(key = "settings-search-results") {
        val results = settingsSearchResults(
            query = query,
            entries = entries(),
        )
        if (results.isEmpty()) {
            SettingsSearchEmptyState(isTablet = isTablet)
        } else {
            SettingsSection(
                title = stringResource(Res.string.settings_search_results_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    results.forEachIndexed { index, entry ->
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        SettingsNavigationRow(
                            title = entry.title,
                            description = entry.resultDescription(),
                            icon = entry.icon,
                            isTablet = isTablet,
                            onClick = { onTargetClick(entry.target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchRevealItem(
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    if (!animate) {
        content()
        return
    }

    val visibleState = remember {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            expandFrom = Alignment.Top,
        ) + fadeIn(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.fastMillis),
        ) + slideInVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            initialOffsetY = { -it / 4 },
        ),
    ) {
        content()
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    isTablet: Boolean,
    onQueryChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val glassEnabled = isLiquidGlassSurfaceEffectivelyEnabled(
        surface = LiquidGlassSurface.SETTINGS,
        platformSupported = isLiquidGlassSupported(),
    )
    val defaultShape = if (isTablet) {
        RoundedCornerShape(NuvioTokens.Radius.xl)
    } else {
        tokens.shapes.compactCard
    }
    val fieldShape = if (glassEnabled) {
        liquidGlassShape(LiquidGlassSurface.SETTINGS, defaultShape)
    } else {
        tokens.shapes.compactCard
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().then(
            if (glassEnabled) {
                Modifier.liquidGlassForSurface(
                    surface = LiquidGlassSurface.SETTINGS,
                    shape = fieldShape,
                    tint = tokens.colors.surface.copy(alpha = 0.45f),
                    fallback = tokens.colors.surface,
                    blurRadius = 14.dp,
                    refractionHeight = 8.dp,
                    refractionAmount = 12.dp,
                )
            } else {
                Modifier
            },
        ),
        singleLine = true,
        shape = fieldShape,
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.compose_search_clear),
                        tint = tokens.colors.textMuted,
                    )
                }
            }
        } else {
            null
        },
        placeholder = {
            Text(
                text = stringResource(Res.string.settings_search_placeholder),
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = if (glassEnabled) tokens.colors.borderSubtle else tokens.colors.borderDefault,
            focusedContainerColor = if (glassEnabled) Color.Transparent else tokens.colors.surfaceCard,
            unfocusedContainerColor = if (glassEnabled) Color.Transparent else tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}

@Composable
private fun SettingsSearchEmptyState(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(
        title = stringResource(Res.string.settings_search_results_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

internal fun settingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val normalizedQuery = normalizeSettingsSearchText(query)
    val queryTerms = normalizedQuery.split(' ').filter(String::isNotBlank).distinct()
    if (queryTerms.isEmpty()) return emptyList()

    return entries.mapIndexedNotNull { index, entry ->
        val score = settingsSearchEntryScore(normalizedQuery, queryTerms, entry)
            ?: return@mapIndexedNotNull null
        RankedSettingsSearchEntry(entry = entry, score = score, sourceIndex = index)
    }
        .sortedWith(
            compareByDescending<RankedSettingsSearchEntry> { it.score }
                .thenBy { it.sourceIndex },
        )
        .map(RankedSettingsSearchEntry::entry)
}

private data class RankedSettingsSearchEntry(
    val entry: SettingsSearchEntry,
    val score: Int,
    val sourceIndex: Int,
)

private data class SettingsSearchIndexField(
    val value: String,
    val weight: Int,
)

private fun settingsSearchEntryScore(
    normalizedQuery: String,
    queryTerms: List<String>,
    entry: SettingsSearchEntry,
): Int? {
    val fields = listOf(
        SettingsSearchIndexField(entry.title, weight = 12),
        SettingsSearchIndexField(entry.searchTerms.joinToString(" "), weight = 10),
        SettingsSearchIndexField(entry.section, weight = 6),
        SettingsSearchIndexField(entry.page, weight = 4),
        SettingsSearchIndexField(entry.description, weight = 3),
        SettingsSearchIndexField(entry.key, weight = 2),
        SettingsSearchIndexField(entry.category, weight = 1),
    ).map { field ->
        normalizeSettingsSearchText(field.value)
            .split(' ')
            .filter(String::isNotBlank) to field.weight
    }.filter { (words, _) -> words.isNotEmpty() }

    var score = 0
    queryTerms.forEach { queryTerm ->
        val bestTermScore = fields.maxOfOrNull { (words, weight) ->
            (words.maxOfOrNull { candidate ->
                settingsSearchTermScore(queryTerm, candidate)
            } ?: 0) * weight
        } ?: 0
        if (bestTermScore == 0) return null
        score += bestTermScore
    }

    val title = normalizeSettingsSearchText(entry.title)
    if (title == normalizedQuery) {
        score += 1_000
    } else if (title.startsWith(normalizedQuery)) {
        score += 500
    } else if (title.contains(normalizedQuery)) {
        score += 250
    }

    val aliases = normalizeSettingsSearchText(entry.searchTerms.joinToString(" "))
    if (aliases == normalizedQuery) {
        score += 750
    } else if (aliases.contains(normalizedQuery)) {
        score += 180
    }

    val page = normalizeSettingsSearchText(entry.page)
    if (page == normalizedQuery) score += 350

    return score
}

/** Common-source normalization: ignore case and punctuation, preserve letters and digits. */
private fun normalizeSettingsSearchText(value: String): String = buildString(value.length) {
    var previousWasSeparator = true
    value.lowercase().forEach { character ->
        if (character.isLetterOrDigit()) {
            append(character)
            previousWasSeparator = false
        } else if (!previousWasSeparator) {
            append(' ')
            previousWasSeparator = true
        }
    }
}.trim()

private fun settingsSearchTermScore(query: String, candidate: String): Int {
    if (query == candidate) return 100
    if (candidate.startsWith(query)) return 80
    if (candidate.length >= 4 && query.startsWith(candidate) && query.length - candidate.length in 1..2) return 74
    if (query.length >= 3 && candidate.contains(query)) return 55

    val maximumDistance = when (query.length) {
        in 0..4 -> 0
        in 5..7 -> 1
        else -> 2
    }
    if (maximumDistance == 0) return 0

    val distance = boundedSettingsSearchEditDistance(query, candidate, maximumDistance)
        ?: return 0
    return 36 - ((distance - 1) * 9)
}

private fun boundedSettingsSearchEditDistance(
    left: String,
    right: String,
    maximumDistance: Int,
): Int? {
    if (kotlin.math.abs(left.length - right.length) > maximumDistance) return null
    if (left.isEmpty()) return right.length.takeIf { it <= maximumDistance }
    if (right.isEmpty()) return left.length.takeIf { it <= maximumDistance }

    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]

        for (rightIndex in right.indices) {
            val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                previous[rightIndex + 1] + 1,
                current[rightIndex] + 1,
                previous[rightIndex] + substitutionCost,
            )
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }

        if (rowMinimum > maximumDistance) return null
        previous = current
    }

    return previous[right.length].takeIf { it <= maximumDistance }
}

private fun SettingsSearchEntry.resultDescription(): String {
    return description.ifBlank { contextLabel }
}
