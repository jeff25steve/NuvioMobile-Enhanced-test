package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    @Test
    fun `first and last indicators select adjacent pages across the loop`() {
        assertEquals(79, heroPageForItem(currentPage = 80, itemIndex = 7, itemCount = 8))
        assertEquals(88, heroPageForItem(currentPage = 87, itemIndex = 0, itemCount = 8))
    }

    @Test
    fun `indicators keep the current page when selecting the active title`() {
        assertEquals(83, heroPageForItem(currentPage = 83, itemIndex = 3, itemCount = 8))
    }

    @Test
    fun `indicators select the closest occurrence of a title`() {
        assertEquals(85, heroPageForItem(currentPage = 83, itemIndex = 5, itemCount = 8))
        assertEquals(81, heroPageForItem(currentPage = 83, itemIndex = 1, itemCount = 8))
    }

    @Test
    fun `mobile hero height stays compact without continue watching`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(452.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero height leaves room for continue watching card section`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(24f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero can shrink below default minimum to fit short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(268f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `playback start notifies every registered observer until it is unregistered`() {
        val notifications = mutableListOf<String>()
        val first: () -> Unit = { notifications += "first" }
        val second: () -> Unit = { notifications += "second" }

        HomeHeroTrailerPlaybackController.registerPlaybackStartObserver(first)
        HomeHeroTrailerPlaybackController.registerPlaybackStartObserver(second)
        try {
            HomeHeroTrailerPlaybackController.notifyPlaybackStarting()
            assertEquals(listOf("first", "second"), notifications)

            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(first)
            notifications.clear()
            HomeHeroTrailerPlaybackController.notifyPlaybackStarting()
            assertEquals(listOf("second"), notifications)
        } finally {
            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(first)
            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(second)
        }
    }

    @Test
    fun `playback start dispatch tolerates an observer unregistering itself`() {
        val notifications = mutableListOf<String>()
        lateinit var firstObserver: () -> Unit
        firstObserver = {
            notifications += "first"
            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(firstObserver)
        }
        val secondObserver: () -> Unit = { notifications += "second" }

        try {
            HomeHeroTrailerPlaybackController.registerPlaybackStartObserver(firstObserver)
            HomeHeroTrailerPlaybackController.registerPlaybackStartObserver(secondObserver)

            HomeHeroTrailerPlaybackController.notifyPlaybackStarting()
            assertEquals(listOf("first", "second"), notifications)

            HomeHeroTrailerPlaybackController.notifyPlaybackStarting()
            assertEquals(listOf("first", "second", "second"), notifications)
        } finally {
            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(firstObserver)
            HomeHeroTrailerPlaybackController.unregisterPlaybackStartObserver(secondObserver)
            HomeHeroTrailerPlaybackController.setInlineTrailerPlaybackActive(false)
        }
    }

}
