package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSearchAnchorTest {
    @Test
    fun anchorMatchesOnlyItsExactSettingKeyOnTheExpectedPage() {
        val anchor = SettingsSearchAnchor(
            page = SettingsPage.Appearance,
            key = "amoled",
            title = "AMOLED black",
            description = "Use a pure black background",
            sectionItemIndex = 1,
        )
        assertTrue(anchor.matches(SettingsPage.Appearance, "amoled"))
        assertFalse(anchor.matches(SettingsPage.Appearance, "app-icon"))
        assertFalse(anchor.matches(SettingsPage.Advanced, "amoled"))
        assertFalse(anchor.matches(SettingsPage.Appearance, null))
    }

    @Test
    fun pageOnlySearchTargetDoesNotInventASettingAnchor() {
        val target = SettingsSearchTarget.Page(SettingsPage.Appearance)
        assertEquals(null, target.anchor)
    }

    @Test
    fun settingSearchTargetCarriesItsStableAnchor() {
        val anchor = settingsSearchAnchor(
            SettingsPage.Appearance,
            "app-language",
            "App language",
            "Choose the language used by Nuvio",
        )!!
        val target = SettingsSearchTarget.Page(SettingsPage.Appearance, anchor)
        assertEquals(SettingsPage.Appearance, target.page)
        assertEquals(anchor, target.anchor)
        assertTrue(target.anchor!!.matches(target.page, "app-language"))
    }

    @Test
    fun onlyVerifiedAppearanceRowsReceiveDeepLinkAnchors() {
        assertNull(settingsSearchAnchor(SettingsPage.Appearance, "unknown-control", "Unknown", ""))
        assertNull(settingsSearchAnchor(SettingsPage.Advanced, "amoled", "AMOLED", ""))
    }

    @Test
    fun anchorIndexAccountsForPhoneAndTabletHeaders() {
        val anchor = settingsSearchAnchor(SettingsPage.Appearance, "amoled", "AMOLED", "")!!
        assertEquals(2, settingsSearchAnchorListIndex(anchor, false, true))
        assertEquals(2, settingsSearchAnchorListIndex(anchor, false, false))
        assertEquals(2, settingsSearchAnchorListIndex(anchor, true, true))
        assertEquals(1, settingsSearchAnchorListIndex(anchor, true, false))
    }
}
