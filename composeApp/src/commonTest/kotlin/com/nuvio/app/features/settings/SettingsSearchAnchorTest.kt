package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchAnchorTest {
    @Test
    fun anchorMatchesOnlyItsExactSettingKeyOnTheExpectedPage() {
        val anchor = SettingsSearchAnchor(
            page = SettingsPage.Appearance,
            key = "amoled",
            title = "AMOLED black",
            description = "Use a pure black background",
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
        val anchor = SettingsSearchAnchor(
            page = SettingsPage.Appearance,
            key = "app-language",
            title = "App language",
            description = "Choose the language used by Nuvio",
        )
        val target = SettingsSearchTarget.Page(
            page = SettingsPage.Appearance,
            anchor = anchor,
        )

        assertEquals(SettingsPage.Appearance, target.page)
        assertEquals(anchor, target.anchor)
        assertTrue(target.anchor!!.matches(target.page, "app-language"))
    }
}
