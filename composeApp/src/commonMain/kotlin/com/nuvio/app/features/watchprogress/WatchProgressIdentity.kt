package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.sync.ProgressDeltaEvent
import com.nuvio.app.features.watching.sync.ProgressSyncRecord

/**
 * Stable storage/sync identity for a progress row.
 *
 * [WatchProgressEntry.videoId] identifies the playable video and is not unique
 * across metadata aliases. The server's progress key identifies the logical
 * row and must therefore be used for snapshot merges and delta deletes.
 */
internal fun buildWatchProgressKey(
    contentId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String =
    if (seasonNumber != null && episodeNumber != null) {
        "${contentId}_s${seasonNumber}e${episodeNumber}"
    } else {
        contentId
    }

internal fun WatchProgressEntry.resolvedProgressKey(): String =
    progressKey
        ?.takeIf(String::isNotBlank)
        ?: buildWatchProgressKey(
            contentId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

internal fun WatchProgressEntry.withResolvedProgressKey(): WatchProgressEntry {
    val resolved = resolvedProgressKey()
    return if (progressKey == resolved) this else copy(progressKey = resolved)
}

internal fun ProgressSyncRecord.resolvedProgressKey(): String =
    progressKey.takeIf(String::isNotBlank) ?: run {
        buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )
    }

internal fun ProgressDeltaEvent.resolvedProgressKey(): String =
    progressKey.takeIf(String::isNotBlank) ?: run {
        buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )
    }

internal val watchProgressEntryFreshnessComparator: Comparator<WatchProgressEntry> =
    compareBy<WatchProgressEntry> { entry -> entry.lastUpdatedEpochMs }
        .thenBy { entry -> entry.lastPositionMs }
        .thenBy { entry -> entry.durationMs }
        .thenBy { entry -> entry.videoId }
        .thenBy { entry -> entry.parentMetaId }
        .thenBy { entry -> entry.contentType }
        .thenBy { entry -> entry.seasonNumber ?: Int.MIN_VALUE }
        .thenBy { entry -> entry.episodeNumber ?: Int.MIN_VALUE }
        .thenBy(WatchProgressEntry::isCompleted)
        .thenBy { entry -> entry.normalizedProgressPercent ?: Float.NEGATIVE_INFINITY }
        .thenBy(WatchProgressEntry::source)
        .thenBy(WatchProgressEntry::parentMetaType)
        .thenBy(WatchProgressEntry::title)
        .thenBy { entry -> entry.logo.orEmpty() }
        .thenBy { entry -> entry.poster.orEmpty() }
        .thenBy { entry -> entry.background.orEmpty() }
        .thenBy { entry -> entry.episodeTitle.orEmpty() }
        .thenBy { entry -> entry.episodeThumbnail.orEmpty() }
        .thenBy { entry -> entry.providerName.orEmpty() }
        .thenBy { entry -> entry.providerAddonId.orEmpty() }
        .thenBy { entry -> entry.lastStreamTitle.orEmpty() }
        .thenBy { entry -> entry.lastStreamSubtitle.orEmpty() }
        .thenBy { entry -> entry.pauseDescription.orEmpty() }
        .thenBy { entry -> entry.lastSourceUrl.orEmpty() }
        .thenBy { entry -> entry.progressKey.orEmpty() }

internal fun WatchProgressEntry.isFresherThan(other: WatchProgressEntry): Boolean =
    watchProgressEntryFreshnessComparator.compare(this, other) > 0

/** Keeps one newest row per logical progress key, independent of input order. */
internal fun Collection<WatchProgressEntry>.newestByProgressKey(): Map<String, WatchProgressEntry> {
    val result = linkedMapOf<String, WatchProgressEntry>()
    forEach { rawEntry ->
        val entry = rawEntry.withResolvedProgressKey()
        val key = entry.resolvedProgressKey()
        val existing = result[key]
        if (existing == null || entry.isFresherThan(existing)) {
            result[key] = entry
        }
    }
    return result
}

/**
 * Reuses an existing opaque server key when playback updates the same logical
 * content row. Exact playback-id matches win; freshness breaks remaining ties.
 */
internal fun Collection<WatchProgressEntry>.resolveIdentityForUpsert(
    candidate: WatchProgressEntry,
): WatchProgressEntry {
    var freshestLogicalMatch: WatchProgressEntry? = null
    var freshestPlaybackMatch: WatchProgressEntry? = null

    for (entry in this) {
        if (
            entry.parentMetaId != candidate.parentMetaId ||
            entry.seasonNumber != candidate.seasonNumber ||
            entry.episodeNumber != candidate.episodeNumber
        ) {
            continue
        }

        if (freshestLogicalMatch == null || entry.isFresherThan(freshestLogicalMatch)) {
            freshestLogicalMatch = entry
        }
        if (
            entry.videoId == candidate.videoId &&
            (freshestPlaybackMatch == null || entry.isFresherThan(freshestPlaybackMatch))
        ) {
            freshestPlaybackMatch = entry
        }
    }

    val existing = freshestPlaybackMatch ?: freshestLogicalMatch
    val progressKey = existing?.resolvedProgressKey() ?: candidate.resolvedProgressKey()
    return candidate.copy(progressKey = progressKey)
}

internal fun Collection<WatchProgressEntry>.resolveProgressForVideo(
    videoId: String,
    parentMetaId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): WatchProgressEntry? {
    var logicalIdentity: Triple<String, Int?, Int?>? = null
    var freshestMatch: WatchProgressEntry? = null

    for (entry in this) {
        if (entry.videoId != videoId) continue
        if (parentMetaId != null && entry.parentMetaId != parentMetaId) continue
        if (seasonNumber != null && entry.seasonNumber != seasonNumber) continue
        if (episodeNumber != null && entry.episodeNumber != episodeNumber) continue

        val candidateIdentity = Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        val existingIdentity = logicalIdentity
        if (existingIdentity == null) {
            logicalIdentity = candidateIdentity
        } else if (candidateIdentity != existingIdentity) {
            return null
        }

        if (freshestMatch == null || entry.isFresherThan(freshestMatch)) {
            freshestMatch = entry
        }
    }

    return freshestMatch
}

internal data class WatchProgressIdentityReconciliation(
    val entries: List<WatchProgressEntry>,
    val migratedKeys: Map<String, String>,
)

/**
 * Migrates a legacy synthetic local key to the server's sole opaque key for
 * the same logical content row. Exact keys always win, and ambiguous server
 * identities are intentionally left untouched.
 */
internal fun reconcileLocalProgressKeysWithSnapshot(
    serverEntries: Collection<ProgressSyncRecord>,
    localEntries: Collection<WatchProgressEntry>,
): WatchProgressIdentityReconciliation {
    val serverKeys = mutableSetOf<String>()
    val uniqueServerKeyByLogicalIdentity = mutableMapOf<Triple<String, Int?, Int?>, String>()
    val ambiguousServerIdentities = mutableSetOf<Triple<String, Int?, Int?>>()

    for (record in serverEntries) {
        val progressKey = record.resolvedProgressKey()
        serverKeys += progressKey

        val identity = Triple(record.contentId, record.season, record.episode)
        if (identity in ambiguousServerIdentities) continue

        val existingKey = uniqueServerKeyByLogicalIdentity[identity]
        if (existingKey == null) {
            uniqueServerKeyByLogicalIdentity[identity] = progressKey
        } else if (existingKey != progressKey) {
            uniqueServerKeyByLogicalIdentity.remove(identity)
            ambiguousServerIdentities += identity
        }
    }
    val migrations = linkedMapOf<String, String>()
    val reconciled = localEntries.map { entry ->
        val localKey = entry.resolvedProgressKey()
        if (localKey in serverKeys) return@map entry

        val syntheticKey = buildWatchProgressKey(
            contentId = entry.parentMetaId,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
        )
        val serverKey = uniqueServerKeyByLogicalIdentity[
            Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        ]
        if (localKey == syntheticKey && serverKey != null && serverKey != localKey) {
            migrations[localKey] = serverKey
            entry.copy(progressKey = serverKey)
        } else {
            entry
        }
    }
    return WatchProgressIdentityReconciliation(
        entries = reconciled,
        migratedKeys = migrations,
    )
}
