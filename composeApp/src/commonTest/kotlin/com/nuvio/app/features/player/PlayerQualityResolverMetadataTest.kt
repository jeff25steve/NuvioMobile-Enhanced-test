package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerQualityResolverMetadataTest {
    @Test
    fun parsesAndNormalizesDeclaredHlsVideoRangeAndFrameRate() {
        assertEquals(
            PlayerQualityVariantMetadata(videoRange = "PQ", frameRate = 23.976),
            parsePlayerQualityVariantMetadata(
                mapOf("VIDEO-RANGE" to " pq ", "FRAME-RATE" to "23.976"),
            ),
        )
        assertEquals(
            PlayerQualityVariantMetadata(videoRange = "HLG", frameRate = 59.94),
            parsePlayerQualityVariantMetadata(
                mapOf("VIDEO-RANGE" to "hLg", "FRAME-RATE" to "59.94"),
            ),
        )
        assertEquals(
            PlayerQualityVariantMetadata(videoRange = "SDR", frameRate = null),
            parsePlayerQualityVariantMetadata(mapOf("VIDEO-RANGE" to "sdr")),
        )
    }

    @Test
    fun omitsUnknownRangeAndInvalidFrameRates() {
        listOf("UNKNOWN", "HDR10", "").forEach { value ->
            assertEquals(
                PlayerQualityVariantMetadata(videoRange = null, frameRate = null),
                parsePlayerQualityVariantMetadata(mapOf("VIDEO-RANGE" to value)),
            )
        }

        listOf("", "invalid", "0", "-24", "NaN", "Infinity").forEach { value ->
            assertEquals(
                PlayerQualityVariantMetadata(videoRange = "PQ", frameRate = null),
                parsePlayerQualityVariantMetadata(
                    mapOf("VIDEO-RANGE" to "PQ", "FRAME-RATE" to value),
                ),
            )
        }
    }

    @Test
    fun rewritesQuotedUriWithoutTreatingDollarTokensAsRegexGroups() {
        val url = "https://cdn.example/video.m3u8?sig=\$1&token=\$token"
        val line = """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8""""

        assertEquals(
            """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="$url"""",
            rewriteHlsAttribute(line, "URI", url),
        )
    }

    @Test
    fun appendsQuotedUriWhenAttributeIsMissing() {
        assertEquals(
            """#EXT-X-MEDIA:TYPE=AUDIO,URI="https://cdn.example/audio.m3u8"""",
            rewriteHlsAttribute("#EXT-X-MEDIA:TYPE=AUDIO", "URI", "https://cdn.example/audio.m3u8"),
        )
    }
}
