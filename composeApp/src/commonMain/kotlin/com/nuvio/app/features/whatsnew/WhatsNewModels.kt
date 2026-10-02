package com.nuvio.app.features.whatsnew

internal enum class WhatsNewNoteCategory {
    FEATURES,
    FIXES,
    PERFORMANCE,
    LOCALIZATION,
    OTHER,
}

internal data class WhatsNewNote(
    val category: WhatsNewNoteCategory,
    val text: String,
)

internal data class WhatsNewRelease(
    val version: String,
    val title: String,
    val notes: List<WhatsNewNote>,
    val publishedAt: String?,
    val releaseUrl: String?,
)

internal data class WhatsNewSnapshot(
    val releases: List<WhatsNewRelease>,
    val currentVersion: String,
    val sinceYourVersion: List<WhatsNewRelease>,
    val hasCompleteSinceVersion: Boolean,
)

internal data class WhatsNewContent(
    val snapshot: WhatsNewSnapshot,
    val fromCache: Boolean,
    val isStale: Boolean,
    val fetchedAtMillis: Long,
)
