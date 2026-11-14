package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncRunPolicyTest {
    @Test
    fun restoredSelectionStartsWithoutAlternativeCandidates() {
        val candidates = listOf(
            AutoSyncSubtitleCandidate("https://example.com/selected.srt", "pol"),
            AutoSyncSubtitleCandidate("https://example.com/alternative.srt", "pol"),
        )

        assertEquals(emptyList(), AutoSyncCandidateScope.SELECTED_THEN_SEARCH.alternativeCandidates(candidates))
        assertFalse(AutoSyncCandidateScope.SELECTED_THEN_SEARCH.usesAlternativeProvider)
    }

    @Test
    fun restoredSelectionSearchesAlternativesAfterAConfirmedNoFit() {
        assertTrue(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_FIT,
            ),
        )
        assertTrue(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.INCONCLUSIVE,
            ),
        )
    }

    @Test
    fun missingReferenceEvidenceDoesNotTriggerAlternativeSearch() {
        assertFalse(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
            ),
        )
        assertFalse(
            AutoSyncCandidateScope.SELECTED_THEN_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
            ),
        )
    }

    @Test
    fun manualSelectionDoesNotFallBackToAlternativeSubtitles() {
        val candidates = listOf(AutoSyncSubtitleCandidate("https://example.com/alternative.srt", "pol"))

        assertEquals(emptyList(), AutoSyncCandidateScope.SELECTED_ONLY.alternativeCandidates(candidates))
        assertFalse(
            AutoSyncCandidateScope.SELECTED_ONLY.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_FIT,
            ),
        )
    }

    @Test
    fun ordinaryStartupSearchStillUsesAlternativeCandidates() {
        val candidate = AutoSyncSubtitleCandidate("https://example.com/alternative.srt", "pol")

        assertEquals(listOf(candidate), AutoSyncCandidateScope.STARTUP_SEARCH.alternativeCandidates(listOf(candidate)))
        assertTrue(AutoSyncCandidateScope.STARTUP_SEARCH.usesAlternativeProvider)
        assertFalse(
            AutoSyncCandidateScope.STARTUP_SEARCH.searchAfterSelectedFailed(
                AutoSyncAnalysisOutcome.NO_FIT,
            ),
        )
    }
}
