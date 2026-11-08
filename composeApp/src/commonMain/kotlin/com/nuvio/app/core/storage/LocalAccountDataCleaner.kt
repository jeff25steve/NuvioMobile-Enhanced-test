package com.nuvio.app.core.storage

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.LiveTvCacheStore
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.profiles.ProfileBiometricAuth
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.SearchHistoryRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.streams.StreamContextStore
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.PinnedStreamSourcesRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.trakt.TraktLibraryRepository

/**
 * Removes every profile-scoped local Liquid Glass background even if one file cannot be deleted.
 * Attempts both image types for every profile and reports incomplete cleanup to the caller.
 */
internal fun clearProfileBackgroundImages(
    profileIndices: IntRange,
    removeSettingsImage: (Int) -> Boolean,
    removeCloudImage: (Int) -> Boolean,
): Boolean {
    var allRemoved = true
    for (profileIndex in profileIndices) {
        val settingsRemoved = try {
            removeSettingsImage(profileIndex)
        } catch (_: Exception) {
            false
        }
        val cloudRemoved = try {
            removeCloudImage(profileIndex)
        } catch (_: Exception) {
            false
        }
        if (!settingsRemoved || !cloudRemoved) allRemoved = false
    }
    return allRemoved
}

internal suspend fun runAccountCleanupSteps(
    steps: List<suspend () -> Unit>,
): List<Exception> {
    val failures = mutableListOf<Exception>()
    steps.forEach { step ->
        try {
            step()
        } catch (error: Exception) {
            failures += error
        }
    }
    return failures
}

/**
 * Runs required local cleanup to completion, then restores normal cancellation semantics.
 *
 * The cleanup block must contain local operations only. Network requests must remain
 * cancellable and run before entering this boundary.
 */
internal suspend fun runNonCancellableCleanup(block: suspend () -> Unit) {
    var cleanupFailure: Throwable? = null
    withContext(NonCancellable) {
        try {
            block()
        } catch (error: Throwable) {
            cleanupFailure = error
        }
    }

    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        cleanupFailure?.let(cancellation::addSuppressed)
        throw cancellation
    }
    cleanupFailure?.let { throw it }
}

internal object LocalAccountDataCleaner {
    suspend fun wipe(accountUserId: String? = null) = runNonCancellableCleanup {
        val cleanupUserId = accountUserId
            ?.takeIf { it.isNotBlank() }
            ?: (AuthRepository.state.value as? AuthState.Authenticated)?.userId

        val steps = listOf<suspend () -> Unit>(
            { MemberAccessRepository.clearLocalState() },
            { EpisodeReleaseNotificationsRepository.clearLocalState() },
            { EpisodeReleaseNotificationsRepository.clearPlatformNotificationsForAccountWipe() },
            {
                val allRemoved = withContext(Dispatchers.IO) {
                    clearProfileBackgroundImages(
                        profileIndices = 1..MAX_PROFILES,
                        removeSettingsImage = { profileIndex ->
                            ThemeSettingsStorage.clearCustomSettingsBackgroundImage(profileIndex)
                        },
                        removeCloudImage = { profileIndex ->
                            ThemeSettingsStorage.clearCustomLibraryCloudBackgroundImage(profileIndex)
                        },
                    )
                }
                check(allRemoved) { "Profile background images could not all be removed" }
            },
            {
                var allRemoved = true
                (1..MAX_PROFILES).forEach { profileIndex ->
                    val removed = try {
                        ProfilePinCacheStorage.removePayload(profileIndex)
                    } catch (_: Exception) {
                        false
                    }
                    if (!removed) allRemoved = false
                }
                check(allRemoved) { "Profile PIN cache data could not all be removed" }
            },
            {
                if (!cleanupUserId.isNullOrBlank()) {
                    val removed = withContext(Dispatchers.IO) {
                        ProfileBiometricAuth.disable(1, cleanupUserId)
                    }
                    check(removed) { "Primary profile biometric credentials could not be removed" }
                }
            },
            { ensureTrackingProvidersRegistered() },
            { TrackingProviderRegistry.removeStoredProfiles(1..MAX_PROFILES) },
            { SyncManager.cancelAccountSync() },
            { WatchProgressSourceCoordinator.clearLocalState() },
            { ProfileSettingsSync.clearAccountState() },
            { ContinueWatchingEnrichmentCache.clearLocalState() },
            { WatchProgressRepository.clearLocalState() },
            { WatchedRepository.clearLocalState() },
            { LiveTvRepository.clearLocalState() },
            {
                val cleared = withContext(Dispatchers.IO) { LiveTvCacheStore.clear() }
                check(cleared) { "Live TV cache data could not be cleared" }
            },
            {
                LibraryRepository.runAccountStorageWipe {
                    wipePlatformStorage()
                }
            },
            { ProfileRepository.clearInMemory() },
            { CustomPosterUrlRepository.clearLocalState() },
            { SearchHistoryRepository.clearLocalState() },
            { TmdbSettingsRepository.onProfileChanged() },
            { MdbListSettingsRepository.onProfileChanged() },
            { DebridSettingsRepository.onProfileChanged() },
            { AddonRepository.clearLocalState() },
            { ServerRepository.clearLocalState() },
            {
                if (AppFeaturePolicy.pluginsEnabled) {
                    PluginRepository.clearLocalState()
                }
            },
            { HomeRepository.clear() },
            { HomeCatalogSettingsRepository.clearLocalState() },
            { MetaScreenSettingsRepository.clearLocalState() },
            { com.nuvio.app.features.shuffle.EpisodeShuffleRepository.clearLocalState() },
            { LibraryRepository.clearLocalState() },
            { LibraryDisplaySettingsRepository.clearLocalState() },
            { ContinueWatchingPreferencesRepository.clearLocalState() },
            { CollectionMobileSettingsRepository.clearLocalState() },
            { CollectionRepository.clearLocalState() },
            { ThemeSettingsRepository.clearLocalState() },
            { PosterCardStyleRepository.clearLocalState() },
            { CardDepthStyleRepository.clearLocalState() },
            { TrackingProviderRegistry.clearLocalState() },
            { TraktLibraryRepository.clearLocalState() },
            { TrackingSettingsRepository.clearLocalState() },
            { PlayerSettingsRepository.clearLocalState() },
            { StreamBadgeSettingsRepository.clearLocalState() },
            { PinnedStreamSourcesRepository.clearLocalState() },
            { TraktCommentsSettings.clearLocalState() },
            { P2pSettingsRepository.clearLocalState() },
            { CatalogRepository.clear() },
            { StreamsRepository.clear() },
            { MetaDetailsRepository.clear() },
            { SearchRepository.reset() },
            { SubtitleRepository.clear() },
            { PlayerLaunchStore.clear() },
            { StreamLaunchStore.clear() },
            { StreamContextStore.clear() },
        )

        val allFailures = mutableListOf<Exception>()
        try {
            allFailures += runAccountCleanupSteps(steps)
        } finally {
            allFailures += runAccountCleanupSteps(
                listOf(
                    { EpisodeReleaseNotificationsRepository.onAccountCleanupFinished() },
                    { MemberAccessRepository.onAccountCleanupFinished() },
                ),
            )
        }

        if (allFailures.isNotEmpty()) {
            val primaryFailure = allFailures.first()
            allFailures.drop(1).forEach(primaryFailure::addSuppressed)
            throw IllegalStateException(
                "Local account cleanup was incomplete (${allFailures.size} steps failed)",
                primaryFailure,
            )
        }
    }

    internal fun wipePlatformStorage(wipeStorage: () -> Unit = PlatformLocalAccountDataCleaner::wipe) {
        try {
            wipeStorage()
        } finally {
            ContinueWatchingEnrichmentCache.clearLocalState()
        }
    }
}

internal expect object PlatformLocalAccountDataCleaner {
    fun wipe()
}
