package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCompletionTransitionTest {
    @Test
    fun enteringCompletedFromAnyNonCompletedStateIsNew() {
        assertTrue(isCompletedTransition(null, DownloadStatus.Completed))
        assertTrue(isCompletedTransition(DownloadStatus.Downloading, DownloadStatus.Completed))
        assertTrue(isCompletedTransition(DownloadStatus.Paused, DownloadStatus.Completed))
        assertTrue(isCompletedTransition(DownloadStatus.Failed, DownloadStatus.Completed))
    }

    @Test
    fun alreadyCompletedOrNonCompletedUpdatesAreNotNewCompletions() {
        assertFalse(isCompletedTransition(DownloadStatus.Completed, DownloadStatus.Completed))
        assertFalse(isCompletedTransition(DownloadStatus.Completed, DownloadStatus.Downloading))
        assertFalse(isCompletedTransition(DownloadStatus.Downloading, DownloadStatus.Paused))
        assertFalse(isCompletedTransition(DownloadStatus.Downloading, DownloadStatus.Failed))
    }
}
