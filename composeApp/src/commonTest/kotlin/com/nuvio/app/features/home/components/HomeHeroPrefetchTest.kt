package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeHeroPrefetchTest {
    @Test
    fun backdropPrefetchUsesMeasuredHeroBoundsAndCropScale() {
        val size = heroPrefetchSize(widthPx = 1080f, heightPx = 1920f)
        val targets = homeHeroPrefetchTargets(
            artworkUrl = "https://example.test/backdrop.jpg",
            logoUrl = null,
            isCardStyle = false,
            artworkSize = size,
            logoSize = null,
        )

        assertEquals(
            listOf(
                HeroPrefetchTarget(
                    url = "https://example.test/backdrop.jpg",
                    size = HeroPrefetchSize(widthPx = 1080, heightPx = 1920),
                    scale = HeroPrefetchScale.CROP,
                ),
            ),
            targets,
        )
    }

    @Test
    fun fullBleedPrefetchIncludesBoundedFitLogo() {
        val targets = homeHeroPrefetchTargets(
            artworkUrl = "https://example.test/backdrop.jpg",
            logoUrl = "https://example.test/logo.png",
            isCardStyle = false,
            artworkSize = HeroPrefetchSize(widthPx = 1080, heightPx = 720),
            logoSize = HeroPrefetchSize(widthPx = 320, heightPx = 123),
        )

        assertEquals(2, targets.size)
        assertEquals(HeroPrefetchScale.CROP, targets[0].scale)
        assertEquals(HeroPrefetchSize(1080, 720), targets[0].size)
        assertEquals(
            HeroPrefetchTarget(
                url = "https://example.test/logo.png",
                size = HeroPrefetchSize(320, 123),
                scale = HeroPrefetchScale.FIT,
            ),
            targets[1],
        )
    }

    @Test
    fun cardStyleDoesNotPrefetchLogo() {
        val targets = homeHeroPrefetchTargets(
            artworkUrl = "https://example.test/backdrop.jpg",
            logoUrl = "https://example.test/logo.png",
            isCardStyle = true,
            artworkSize = HeroPrefetchSize(widthPx = 1080, heightPx = 720),
            logoSize = HeroPrefetchSize(widthPx = 320, heightPx = 123),
        )

        assertEquals(
            listOf(
                HeroPrefetchTarget(
                    url = "https://example.test/backdrop.jpg",
                    size = HeroPrefetchSize(1080, 720),
                    scale = HeroPrefetchScale.CROP,
                ),
            ),
            targets,
        )
    }

    @Test
    fun logoPrefetchRespectsHorizontalPaddingFractionAndMaximumWidth() {
        val size = heroLogoPrefetchSize(
            heroWidthPx = 1000f,
            horizontalStartPaddingPx = 40f,
            horizontalEndPaddingPx = 60f,
            contentWidthFraction = 0.6f,
            contentMaxWidthPx = 400f,
            logoWidthFraction = 0.8f,
        )

        assertEquals(HeroPrefetchSize(widthPx = 320, heightPx = 123), size)
    }

    @Test
    fun narrowViewportLogoPrefetchStaysWithinAvailableWidth() {
        val size = heroLogoPrefetchSize(
            heroWidthPx = 400f,
            horizontalStartPaddingPx = 24f,
            horizontalEndPaddingPx = 24f,
            contentWidthFraction = 1f,
            contentMaxWidthPx = 480f,
            logoWidthFraction = 0.65f,
        )

        assertEquals(HeroPrefetchSize(widthPx = 229, heightPx = 88), size)
    }

    @Test
    fun invalidOrZeroLogoBoundsSkipOptionalPrefetch() {
        assertNull(
            heroLogoPrefetchSize(
                heroWidthPx = 300f,
                horizontalStartPaddingPx = 150f,
                horizontalEndPaddingPx = 150f,
                contentWidthFraction = 1f,
                contentMaxWidthPx = 200f,
                logoWidthFraction = 0.5f,
            ),
        )
        assertNull(heroPrefetchSize(widthPx = Float.NaN, heightPx = 200f))
        assertNull(heroPrefetchSize(widthPx = 200f, heightPx = 0f))
    }

    @Test
    fun blankUrlsAndInvalidSizesDoNotCreateOriginalSizeFallbacks() {
        val targets = homeHeroPrefetchTargets(
            artworkUrl = " ",
            logoUrl = "https://example.test/logo.png",
            isCardStyle = false,
            artworkSize = null,
            logoSize = null,
        )

        assertEquals(emptyList(), targets)
    }
}
