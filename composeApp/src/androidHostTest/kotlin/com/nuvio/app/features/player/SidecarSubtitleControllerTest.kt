package com.nuvio.app.features.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import kotlin.test.Test
import kotlin.test.assertEquals

class SidecarSubtitleControllerTest {
    @Test
    fun unsortedCueListDoesNotStopAtFirstFutureCue() {
        val laterCue = CuesWithTiming(
            listOf(Cue.Builder().setText("later").build()),
            10_000_000L,
            5_000_000L,
        )
        val activeLaterCue = CuesWithTiming(
            listOf(Cue.Builder().setText("active-later").build()),
            2_000_000L,
            10_000_000L,
        )
        val activeEarlierCue = CuesWithTiming(
            listOf(Cue.Builder().setText("active-earlier").build()),
            0L,
            10_000_000L,
        )

        val active = legacyActiveSidecarEntries(
            listOf(laterCue, activeLaterCue, activeEarlierCue),
            positionUs = 5_000_000L,
        )

        assertEquals(
            listOf("active-earlier", "active-later"),
            active.flatMap { it.cues }.map { it.text.toString() },
        )
    }
}
