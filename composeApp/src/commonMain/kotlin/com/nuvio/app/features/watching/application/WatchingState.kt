package com.nuvio.app.features.watching.application

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import com.nuvio.app.features.watching.domain.WatchingCompletedEpisode
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.normalizeSeasonNumber

object WatchingState {
    fun isPosterWatched(
        watchedKeys: Set<String>,
        item: MetaPreview,
        fullyWatchedSeriesKeys: Set<String> = emptySet(),
    ): Boolean {
        val posterKeys = watchedItemKeys(type = item.type, id = item.id)
        if (posterKeys.any(watchedKeys::contains)) return true
        return item.type.isSeriesLikePosterType() && posterKeys.any(fullyWatchedSeriesKeys::contains)
    }

    fun isEpisodeWatched(
        watchedKeys: Set<String>,
        metaType: String,
        metaId: String,
        episode: MetaVideo,
    ): Boolean {
        val keys = watchedItemKeys(
            type = metaType,
            id = metaId,
            season = episode.season,
            episode = episode.episode,
        )
        if (keys.any(watchedKeys::contains)) return true

        // Fallback for franchise-parent anime: meta.id (e.g. "mal:49233") may differ
        // from the actual entry ID in Simkl. Check via video ID resolution in snapshot.
        // Only for anime-style video IDs (mal:, kitsu:, etc.) — not IMDB/TVDB content.
        val videoId = episode.id
        val episodeNumber = episode.episode
        if (episodeNumber != null) {
            return com.nuvio.app.features.simkl.SimklAnimeWatchedFallback.isWatched(videoId, episodeNumber)
        }
        return false
    }

    fun areEpisodesWatched(
        watchedKeys: Set<String>,
        metaType: String,
        metaId: String,
        episodes: Collection<MetaVideo>,
    ): Boolean = episodes.isNotEmpty() && episodes.all { episode ->
        isEpisodeWatched(
            watchedKeys = watchedKeys,
            metaType = metaType,
            metaId = metaId,
            episode = episode,
        )
    }

    fun latestCompletedBySeries(
        progressEntries: List<WatchProgressEntry>,
        watchedItems: List<WatchedItem>,
        preferFurthestEpisode: Boolean = true,
    ): Map<WatchingContentRef, WatchingCompletedEpisode> {
        // Keep first-seen key order while selecting the best completion in one pass.
        // Retaining grouped lists only to scan them again for one maximum is unnecessary.
        val contentRefs = LinkedHashSet<WatchingContentRef>()
        val latestCompletedByContent = LinkedHashMap<WatchingContentRef, WatchingCompletedEpisode>()

        progressEntries.forEach { entry ->
            val content = WatchingContentRef(type = entry.parentMetaType, id = entry.parentMetaId)
            contentRefs += content

            val normalizedEntry = entry.normalizedCompletion()
            if (normalizedEntry.shouldUseAsCompletedSeedForContinueWatching()) {
                val seasonNumber = normalizedEntry.seasonNumber
                val episodeNumber = normalizedEntry.episodeNumber
                if (seasonNumber != null && episodeNumber != null) {
                    latestCompletedByContent.considerCompletion(
                        content = content,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        markedAtEpochMs = normalizedEntry.lastUpdatedEpochMs,
                        preferFurthestEpisode = preferFurthestEpisode,
                    )
                }
            }
        }

        watchedItems.forEach { item ->
            val content = WatchingContentRef(type = item.type, id = item.id)
            contentRefs += content

            val seasonNumber = item.season
            val episodeNumber = item.episode
            if (seasonNumber != null && episodeNumber != null) {
                latestCompletedByContent.considerCompletion(
                    content = content,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    markedAtEpochMs = normalizeWatchedMarkedAtEpochMs(item.markedAtEpochMs),
                    preferFurthestEpisode = preferFurthestEpisode,
                )
            }
        }

        val result = LinkedHashMap<WatchingContentRef, WatchingCompletedEpisode>()
        contentRefs.forEach { content ->
            latestCompletedByContent[content]?.let { completed -> result[content] = completed }
        }
        return result
    }

    fun visibleContinueWatchingEntries(
        progressEntries: List<WatchProgressEntry>,
        @Suppress("UNUSED_PARAMETER")
        latestCompletedBySeries: Map<WatchingContentRef, WatchingCompletedEpisode>,
    ): List<WatchProgressEntry> = progressEntries.continueWatchingEntries()
}

private fun String.isSeriesLikePosterType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")


private fun MutableMap<WatchingContentRef, WatchingCompletedEpisode>.considerCompletion(
    content: WatchingContentRef,
    seasonNumber: Int,
    episodeNumber: Int,
    markedAtEpochMs: Long,
    preferFurthestEpisode: Boolean,
) {
    val current = this[content]
    if (current == null || isPreferredCompletion(
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            markedAtEpochMs = markedAtEpochMs,
            current = current,
            preferFurthestEpisode = preferFurthestEpisode,
        )
    ) {
        this[content] = WatchingCompletedEpisode(seasonNumber, episodeNumber, markedAtEpochMs)
    }
}

private fun isPreferredCompletion(
    seasonNumber: Int,
    episodeNumber: Int,
    markedAtEpochMs: Long,
    current: WatchingCompletedEpisode,
    preferFurthestEpisode: Boolean,
): Boolean {
    if (preferFurthestEpisode) {
        val seasonComparison = normalizeSeasonNumber(seasonNumber)
            .compareTo(normalizeSeasonNumber(current.seasonNumber))
        if (seasonComparison != 0) return seasonComparison > 0
        val episodeComparison = episodeNumber.compareTo(current.episodeNumber)
        if (episodeComparison != 0) return episodeComparison > 0
        return markedAtEpochMs > current.markedAtEpochMs
    }

    val timestampComparison = markedAtEpochMs.compareTo(current.markedAtEpochMs)
    if (timestampComparison != 0) return timestampComparison > 0
    val seasonComparison = normalizeSeasonNumber(seasonNumber)
        .compareTo(normalizeSeasonNumber(current.seasonNumber))
    if (seasonComparison != 0) return seasonComparison > 0
    return episodeNumber > current.episodeNumber
}
