package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.toSimklShowIdSiblings
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val BADGE_RESOLUTION_CONCURRENCY = 2
private const val AMBIGUOUS_MARKER = "__ambiguous__"

private val log = Logger.withTag("WatchedBadgeBulk")

suspend fun resolveWatchedBadgesBulk(
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
): Boolean = withContext(Dispatchers.Default) {
    val touchedSeriesIds = buildSet {
        watchedItems.forEach { item ->
            if (item.type.isSeriesLikeWatchedType() && item.season != null && item.episode != null) {
                add(item.id)
            }
        }
        progressEntries.forEach { entry ->
            if (entry.parentMetaType.isSeriesLikeWatchedType() && entry.isEpisode && entry.isEffectivelyCompleted) {
                add(entry.parentMetaId)
            }
        }
        WatchedRepository.baseFullyWatchedSeriesKeys().mapNotNullTo(this, ::extractContentIdFromWatchedKey)
    }
    if (touchedSeriesIds.isEmpty()) return@withContext true

    // Use the full watchedKeys from UI state which includes extra keys from
    // provider alternate IDs (e.g. Simkl anime alternate MAL/Kitsu keys).
    val watchedKeys = WatchedRepository.uiState.value.watchedKeys
    // Episode completion is queried repeatedly across series metadata. Index the
    // completed playback IDs once rather than scanning all progress rows per episode.
    val completedPlaybackIds = completedPlaybackVideoIds(progressEntries)

    log.i { "Bulk badge resolution starting: ${touchedSeriesIds.size} series candidates" }

    val semaphore = Semaphore(BADGE_RESOLUTION_CONCURRENCY)
    val resolvedIds = mutableSetOf<String>()
    val resolvedStates = linkedMapOf<String, Boolean>()

    for (contentId in touchedSeriesIds) {
        semaphore.withPermit {
            val meta = try {
                MetaDetailsRepository.fetch(type = "series", id = contentId, cacheResult = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (meta != null) {
                val isFullyWatched = WatchedRepository.calculateFullyWatchedSeriesState(
                    meta = meta,
                    todayIsoDate = todayIsoDate,
                    isEpisodeWatched = { episode ->
                        val keys = watchedItemKeys(meta.type, meta.id, episode.season, episode.episode)
                        if (keys.any(watchedKeys::contains)) {
                            true
                        } else {
                            val episodeNumber = episode.episode
                            if (episodeNumber != null) {
                                com.nuvio.app.features.simkl.SimklAnimeWatchedFallback.isWatched(episode.id, episodeNumber)
                            } else {
                                false
                            }
                        }
                    },
                    isEpisodeCompleted = { episode ->
                        val playbackId = meta.episodePlaybackId(episode)
                        playbackId in completedPlaybackIds
                    },
                )
                resolvedStates[watchedItemKey(meta.type, meta.id)] = isFullyWatched
                if (meta.type.isSeriesLikeWatchedType() && meta.videos.isNotEmpty()) {
                    resolvedIds.add(contentId)
                }
            }
        }
        yield()
    }

    WatchedRepository.updateFullyWatchedSeriesStates(resolvedStates)
    log.i { "Bulk badge resolution complete: resolved ${resolvedIds.size}/${touchedSeriesIds.size}" }

    // Sibling expansion
    expandFullyWatchedWithSiblings()
    resolvedIds.size == touchedSeriesIds.size
}

fun expandFullyWatchedWithSiblings() {
    val siblingMap = getActiveProviderSiblingMap()
    if (siblingMap.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Use base keys (without previous sibling expansion) to avoid feedback loops
    val baseKeys = WatchedRepository.baseFullyWatchedSeriesKeys()
    if (baseKeys.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Compute only the sibling-derived keys (not including base keys themselves)
    val siblingKeys = buildSet {
        for (key in baseKeys) {
            val contentId = extractContentIdFromWatchedKey(key) ?: continue
            val siblings = siblingMap[contentId] ?: continue
            siblings.forEach { siblingId ->
                if (siblingId != contentId && !siblingId.startsWith(AMBIGUOUS_MARKER)) {
                    val siblingKey = rebuildWatchedKeyWithSiblingId(key, siblingId)
                    if (siblingKey != null) add(siblingKey)
                }
            }
        }
    }

    if (siblingKeys != WatchedRepository.currentExpandedSiblingKeys()) {
        log.i { "Sibling expansion: ${baseKeys.size} base keys -> +${siblingKeys.size} sibling keys" }
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(siblingKeys)
    }
}

private fun getActiveProviderSiblingMap(): Map<String, Set<String>> {
    val source = TrackingSettingsRepository.uiState.value.watchProgressSource
    val effectiveSource = effectiveWatchProgressSource(
        requestedSource = source,
        isProviderAuthenticated = { providerId ->
            com.nuvio.app.features.tracking.TrackingProviderRegistry.isAuthenticated(providerId)
        },
    )
    return effectiveSource.providerId?.let {
        com.nuvio.app.features.tracking.TrackingProviderRegistry.progressProvider(it)?.showIdSiblings()
    }.orEmpty()
}

internal fun completedPlaybackVideoIds(entries: Collection<WatchProgressEntry>): Set<String> {
    val result = HashSet<String>()
    for (entry in entries) {
        if (entry.isEffectivelyCompleted) {
            result += entry.videoId
        }
    }
    return result
}

/**
 * Extracts the content ID from a "type:contentId:season:episode" key.
 * Finds the numeric suffix from the end so provider IDs containing colons remain intact.
 */
internal fun extractContentIdFromWatchedKey(key: String): String? {
    val episodeSeparator = key.lastIndexOf(':')
    if (episodeSeparator < 0) return null
    val seasonSeparator = key.lastIndexOf(':', episodeSeparator - 1)
    if (seasonSeparator < 0) return null

    val typeSeparator = key.indexOf(':')
    if (typeSeparator < 0 || seasonSeparator <= typeSeparator) return null
    if (key.substring(seasonSeparator + 1, episodeSeparator).toIntOrNull() == null) return null
    if (key.substring(episodeSeparator + 1).toIntOrNull() == null) return null

    val contentId = key.substring(typeSeparator + 1, seasonSeparator)
    return contentId.takeIf { it.isNotBlank() }
}

/** Rebuilds a fully-watched key while keeping the original type prefix. */
internal fun rebuildWatchedKeyWithSiblingId(originalKey: String, siblingId: String): String? {
    val typeSeparator = originalKey.indexOf(':')
    if (typeSeparator < 0) return null
    val seasonSeparator = originalKey.indexOf(':', typeSeparator + 1)
    if (seasonSeparator < 0) return null
    if (originalKey.indexOf(':', seasonSeparator + 1) < 0) return null

    val type = originalKey.substring(0, typeSeparator)
    return watchedItemKey(type = type, id = siblingId)
}

private fun String.isSeriesLikeWatchedType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")
