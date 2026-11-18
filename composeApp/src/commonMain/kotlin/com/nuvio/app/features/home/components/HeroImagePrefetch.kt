package com.nuvio.app.features.home.components

import kotlin.math.roundToInt

private const val HERO_LOGO_ASPECT_RATIO = 2.6f

internal enum class HeroPrefetchScale {
    CROP,
    FIT,
}

internal data class HeroPrefetchSize(
    val widthPx: Int,
    val heightPx: Int,
)

internal data class HeroPrefetchTarget(
    val url: String,
    val size: HeroPrefetchSize,
    val scale: HeroPrefetchScale,
)

internal fun heroPrefetchSize(widthPx: Float, heightPx: Float): HeroPrefetchSize? {
    if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) {
        return null
    }

    return HeroPrefetchSize(
        widthPx = widthPx.roundToInt().coerceAtLeast(1),
        heightPx = heightPx.roundToInt().coerceAtLeast(1),
    )
}

internal fun heroLogoPrefetchSize(
    heroWidthPx: Float,
    horizontalStartPaddingPx: Float,
    horizontalEndPaddingPx: Float,
    contentWidthFraction: Float,
    contentMaxWidthPx: Float,
    logoWidthFraction: Float,
): HeroPrefetchSize? {
    if (
        !heroWidthPx.isFinite() ||
        !horizontalStartPaddingPx.isFinite() ||
        !horizontalEndPaddingPx.isFinite() ||
        !contentWidthFraction.isFinite() ||
        !contentMaxWidthPx.isFinite() ||
        !logoWidthFraction.isFinite() ||
        heroWidthPx <= 0f ||
        horizontalStartPaddingPx < 0f ||
        horizontalEndPaddingPx < 0f ||
        contentWidthFraction <= 0f ||
        contentWidthFraction > 1f ||
        contentMaxWidthPx <= 0f ||
        logoWidthFraction <= 0f ||
        logoWidthFraction > 1f
    ) {
        return null
    }

    val availableWidthPx = heroWidthPx - horizontalStartPaddingPx - horizontalEndPaddingPx
    if (availableWidthPx <= 0f) return null

    val contentWidthPx = minOf(
        availableWidthPx * contentWidthFraction,
        contentMaxWidthPx,
    )
    val logoWidthPx = contentWidthPx * logoWidthFraction
    return heroPrefetchSize(
        widthPx = logoWidthPx,
        heightPx = logoWidthPx / HERO_LOGO_ASPECT_RATIO,
    )
}

internal fun homeHeroPrefetchTargets(
    artworkUrl: String?,
    logoUrl: String?,
    isCardStyle: Boolean,
    artworkSize: HeroPrefetchSize?,
    logoSize: HeroPrefetchSize?,
): List<HeroPrefetchTarget> = buildList {
    val validArtworkSize = artworkSize?.takeIf { it.widthPx > 0 && it.heightPx > 0 }
    if (!artworkUrl.isNullOrBlank() && validArtworkSize != null) {
        add(
            HeroPrefetchTarget(
                url = artworkUrl,
                size = validArtworkSize,
                scale = HeroPrefetchScale.CROP,
            ),
        )
    }

    val validLogoSize = logoSize?.takeIf { it.widthPx > 0 && it.heightPx > 0 }
    if (!isCardStyle && !logoUrl.isNullOrBlank() && validLogoSize != null) {
        add(
            HeroPrefetchTarget(
                url = logoUrl,
                size = validLogoSize,
                scale = HeroPrefetchScale.FIT,
            ),
        )
    }
}
