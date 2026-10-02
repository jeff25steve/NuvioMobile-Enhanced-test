package com.nuvio.app.features.whatsnew

internal data class WhatsNewRelease(
    val version: String,
    val title: String,
    val notes: String,
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
)
