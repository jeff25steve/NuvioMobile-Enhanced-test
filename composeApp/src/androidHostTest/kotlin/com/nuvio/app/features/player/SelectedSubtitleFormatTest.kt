package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(UnstableApi::class)
class SelectedSubtitleFormatTest {
    @Test
    fun selectedSubtitleFormatUsesSelectedTrackInsteadOfTrackZero() {
        val unselected = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val selected = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .build()
        val group = Tracks.Group(
            TrackGroup(unselected, selected),
            false,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
            booleanArrayOf(false, true),
        )

        assertEquals(selected, selectedSubtitleFormat(Tracks(listOf(group))))
    }

    @Test
    fun returnsNullWhenNoSubtitleTrackIsSelected() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        val group = Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )

        assertNull(selectedSubtitleFormat(Tracks(listOf(group))))
    }

    @Test
    fun ignoresSelectedAudioGroupsWhenFindingSubtitleFormat() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        val text = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        val audioGroup = Tracks.Group(
            TrackGroup(audio),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
        val textGroup = Tracks.Group(
            TrackGroup(text),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )

        assertEquals(text, selectedSubtitleFormat(Tracks(listOf(audioGroup, textGroup))))
    }
}
