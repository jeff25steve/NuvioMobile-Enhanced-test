package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PosterPrefetchScaleTest {
    @Test
    fun regularCatalogueArtworkUsesCropScale() {
        assertEquals(
            PosterPrefetchScale.CROP,
            posterPrefetchScale(isCardStyle = false, isCloudLibraryItem = false),
        )
    }

    @Test
    fun regularContinueWatchingCardUsesCropScale() {
        assertEquals(
            PosterPrefetchScale.CROP,
            posterPrefetchScale(isCardStyle = true, isCloudLibraryItem = false),
        )
    }

    @Test
    fun cloudLibraryCardLayoutStillUsesCropScale() {
        assertEquals(
            PosterPrefetchScale.CROP,
            posterPrefetchScale(isCardStyle = true, isCloudLibraryItem = true),
        )
    }

    @Test
    fun cloudWideArtworkUsesFitScale() {
        assertEquals(
            PosterPrefetchScale.FIT,
            posterPrefetchScale(isCardStyle = false, isCloudLibraryItem = true),
        )
    }
}
