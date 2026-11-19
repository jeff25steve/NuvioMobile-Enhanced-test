package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerQualitySelectionStateTest {
    private val sourceUrl = "https://example.com/master.m3u8?token=abc"

    private fun qualityState(adaptive: Boolean = false) = PlayerQualitySelectionState(
        sourceUrl = sourceUrl,
        variants = listOf(
            PlayerQualityVariant(
                id = "1080",
                name = "1080p",
                width = 1920,
                height = 1080,
                bandwidth = 8_000_000L,
                codecs = "avc1.640028",
                audioGroupId = null,
                absoluteUri = "https://example.com/1080.m3u8",
                playbackUrl = "file:///cache/1080.m3u8",
            ),
            PlayerQualityVariant(
                id = "720",
                name = "720p",
                width = 1280,
                height = 720,
                bandwidth = 4_000_000L,
                codecs = "avc1.64001f",
                audioGroupId = null,
                absoluteUri = "https://example.com/720.m3u8",
                playbackUrl = "file:///cache/720.m3u8",
            ),
        ),
        recommendedVariantId = "1080",
        adaptiveAutoSelection = adaptive,
        isResolved = true,
    )

    @Test
    fun adaptiveAutoUsesTheOriginalMultivariantMasterPlaylist() {
        val state = qualityState(adaptive = true)

        assertEquals(sourceUrl, state.playbackUrlFor(null))
        assertEquals(sourceUrl, state.playbackUrlFor(PlayerQualityAutoId))
    }

    @Test
    fun adaptiveAutoDoesNotPresentTheRecommendedVariantAsLockedQuality() {
        val state = qualityState(adaptive = true)

        assertEquals(null, state.selectedVariantForDisplay(null))
        assertEquals(null, state.selectedVariantForDisplay(PlayerQualityAutoId))
    }

    @Test
    fun manualQualityDisplaysItsSelectedVariant() {
        val state = qualityState(adaptive = true)

        assertEquals("720", state.selectedVariantForDisplay("720")?.id)
    }

    @Test
    fun explicitQualityStillUsesItsSanitizedSingleVariantPlaylist() {
        val state = qualityState(adaptive = true)

        assertEquals("file:///cache/720.m3u8", state.playbackUrlFor("720"))
    }

    @Test
    fun legacyAutoStillUsesTheRecommendedCompatibleVariant() {
        val state = qualityState()

        assertEquals("file:///cache/1080.m3u8", state.playbackUrlFor(null))
    }

    @Test
    fun hlsQualityInspectionIsLazyUntilTheReadyPlayerOpensTheQualityPanel() {
        val pending = PlayerQualitySelectionState(
            isLoading = true,
            sourceUrl = sourceUrl,
        )

        assertFalse(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = false,
                qualityPanelVisible = true,
                initialLoadCompleted = true,
                qualityState = pending,
            ),
        )
        assertFalse(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = true,
                qualityPanelVisible = false,
                initialLoadCompleted = true,
                qualityState = pending,
            ),
        )
        assertFalse(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = true,
                qualityPanelVisible = true,
                initialLoadCompleted = false,
                qualityState = pending,
            ),
        )
        assertTrue(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = true,
                qualityPanelVisible = true,
                initialLoadCompleted = true,
                qualityState = pending,
            ),
        )
    }

    @Test
    fun completedQualityInspectionIsNotRepeatedAfterPlayerReload() {
        val resolved = PlayerQualitySelectionState(
            sourceUrl = sourceUrl,
            isResolved = true,
        )

        assertFalse(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = true,
                qualityPanelVisible = true,
                initialLoadCompleted = true,
                qualityState = resolved,
            ),
        )
    }

    @Test
    fun sourceWithoutAnHlsInspectionCanStartAsAlreadyResolved() {
        val resolved = PlayerQualitySelectionState(
            sourceUrl = "https://example.com/movie.mp4",
            isResolved = true,
        )

        assertFalse(resolved.isLoading)
        assertFalse(
            shouldResolveHlsQualityForPanel(
                nativeAdaptivePlayback = false,
                qualityPanelVisible = true,
                initialLoadCompleted = true,
                qualityState = resolved,
            ),
        )
    }
}
