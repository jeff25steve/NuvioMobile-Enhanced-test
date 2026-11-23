package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerQualityVariantMetadataTest {
    @Test
    fun videoRangeLabelsAreCaseInsensitiveAndDoNotOverstateTheFormat() {
        assertEquals("HDR (PQ)", playerVideoRangeLabel("PQ"))
        assertEquals("HDR (PQ)", playerVideoRangeLabel("pq"))
        assertEquals("HDR (HLG)", playerVideoRangeLabel("hLg"))
        assertEquals("SDR", playerVideoRangeLabel("sdr"))
        assertNull(playerVideoRangeLabel(null))
        assertNull(playerVideoRangeLabel("UNKNOWN"))
        assertNull(playerVideoRangeLabel("HDR10"))
    }

    @Test
    fun frameRateAcceptsOnlyFinitePositiveValues() {
        assertEquals(23.976, parsePlayerFrameRate("23.976"))
        assertEquals(59.94, parsePlayerFrameRate("59.94"))
        assertNull(parsePlayerFrameRate(null))
        assertNull(parsePlayerFrameRate(""))
        assertNull(parsePlayerFrameRate("not-a-number"))
        assertNull(parsePlayerFrameRate("0"))
        assertNull(parsePlayerFrameRate("-24"))
        assertNull(parsePlayerFrameRate("NaN"))
        assertNull(parsePlayerFrameRate("Infinity"))
    }

    @Test
    fun displayLabelIncludesRangeAndFrameRateAlongsideExistingMetadata() {
        val variant = qualityVariant(
            videoRange = "PQ",
            frameRate = 23.976,
        )

        assertEquals(
            "1080p · HDR (PQ) · 23.98 fps · H.264 · 8 Mbps",
            variant.displayLabel,
        )
    }

    @Test
    fun displayLabelLeavesVariantsWithoutOptionalMetadataUnchanged() {
        assertEquals(
            "1080p · H.264 · 8 Mbps",
            qualityVariant().displayLabel,
        )
    }

    @Test
    fun displayLabelOmitsUnknownRangeAndInvalidFrameRate() {
        val variant = qualityVariant(
            videoRange = "UNKNOWN",
            frameRate = Double.NaN,
        )

        assertEquals(
            "1080p · H.264 · 8 Mbps",
            variant.displayLabel,
        )
    }

    private fun qualityVariant(
        videoRange: String? = null,
        frameRate: Double? = null,
    ) = PlayerQualityVariant(
        id = "1080",
        name = "1080p",
        width = 1920,
        height = 1080,
        bandwidth = 8_000_000L,
        codecs = "avc1.640028",
        audioGroupId = null,
        absoluteUri = "https://example.com/1080.m3u8",
        playbackUrl = "file:///cache/1080.m3u8",
        videoRange = videoRange,
        frameRate = frameRate,
    )
}
