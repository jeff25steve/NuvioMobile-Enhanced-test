package com.nuvio.app.core.ui

internal enum class PosterPrefetchScale {
    CROP,
    FIT,
}

internal fun posterPrefetchScale(
    isCardStyle: Boolean,
    isCloudLibraryItem: Boolean,
): PosterPrefetchScale = if (isCloudLibraryItem && !isCardStyle) {
    PosterPrefetchScale.FIT
} else {
    PosterPrefetchScale.CROP
}
