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
    fun av1DecoderCapabilityIsUnknownInsteadOfBeingMislabelledAsSoftwareOnly() {
        val variant = qualityVariant(codecs = "av01.0.08M.08", name = "2160p", height = 2160)

        assertEquals(true, variant.isAv1)
        assertNull(variant.isLikelyHardwareDecodable)
        assertEquals("2160p · AV1 · 8 Mbps", variant.displayLabel)
    }

    @Test
    fun av1VariantWithUnknownHardwareCapabilityRemainsEligibleForRecommendation() {
        val av1 = qualityVariant(codecs = "av01.0.08M.08", name = "2160p", height = 2160, bandwidth = 18_000_000L)
        val h264 = qualityVariant()

        assertEquals(av1, PlayerQualityResolver.chooseRecommendedVariant(listOf(h264, av1)))
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
        codecs: String = "avc1.640028",
        name: String = "1080p",
        height: Int = 1080,
        bandwidth: Long = 8_000_000L,
    ) = PlayerQualityVariant(
        id = name,
        name = name,
        width = if (height >= 2160) 3840 else 1920,
        height = height,
        bandwidth = bandwidth,
        codecs = codecs,
        audioGroupId = null,
        absoluteUri = "https://example.com/1080.m3u8",
        playbackUrl = "file:///cache/1080.m3u8",
        videoRange = videoRange,
        frameRate = frameRate,
    )
}
