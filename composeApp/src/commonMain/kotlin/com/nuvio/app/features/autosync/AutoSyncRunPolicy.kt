package com.nuvio.app.features.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,

    /** Check the restored selection first, then search alternatives only if it fails. */
    SELECTED_THEN_SEARCH,
}

/** A restored selection may fall back only when the video supplied a usable reference. */
internal fun AutoSyncCandidateScope.shouldSearchAfterSelectedFailure(
    hasUsableReference: Boolean,
): Boolean = this == AutoSyncCandidateScope.SELECTED_THEN_SEARCH && hasUsableReference

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null
