package com.nuvio.app.features.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionDecoderSelectionTest {
    @Test
    fun softMatchIsUsedOnlyForDolbyVisionOnDolbyVisionDisplay() {
        assertTrue(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_DOLBY_VISION,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_DOLBY_VISION,
                displaySupportsDolbyVision = false,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                MimeTypes.VIDEO_H265,
                displaySupportsDolbyVision = true,
            ),
        )
        assertFalse(
            shouldUseSoftMatchDolbyVisionDecoder(
                null,
                displaySupportsDolbyVision = true,
            ),
        )
    }

    @Test
    fun trackLabelPrefersTheLocalizedMedia3Name() {
        assertEquals(
            "English (Stereo)",
            resolvePlaybackTrackLabel(
                preferred = "English (Stereo)",
                formatLabel = "Main audio",
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
    }

    @Test
    fun trackLabelFallsBackThroughAvailableFormatMetadata() {
        assertEquals(
            "Main audio",
            resolvePlaybackTrackLabel(
                preferred = " ",
                formatLabel = "Main audio",
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "en",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = "en",
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "ec-3",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = "ec-3",
                id = "2",
                index = 1,
            ),
        )
        assertEquals(
            "2",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = null,
                id = "2",
                index = 1,
            ),
        )
    }

    @Test
    fun trackLabelUsesStableOrdinalWhenAllMetadataIsMissing() {
        assertEquals(
            "4",
            resolvePlaybackTrackLabel(
                preferred = null,
                formatLabel = null,
                language = null,
                codec = null,
                id = null,
                index = 3,
            ),
        )
    }
}
