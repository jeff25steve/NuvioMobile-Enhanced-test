package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.ui.CustomThemeColors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeSettingsStorageTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ThemeSettingsStorage.initialize(context)
    }

    @Test
    fun glowPreferenceSurvivesReloadAndSync() {
        ThemeSettingsStorage.saveNavBarGlowEnabled(false)
        ThemeSettingsStorage.initialize(RuntimeEnvironment.getApplication())
        assertEquals(false, ThemeSettingsStorage.loadNavBarGlowEnabled())
        val payload = ThemeSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("nav_bar_glow_enabled"))

        ThemeSettingsStorage.saveNavBarGlowEnabled(true)
        ThemeSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(false, ThemeSettingsStorage.loadNavBarGlowEnabled())
    }

    @Test
    fun olderPayloadClearsOnlyTheCurrentProfilesGlowPreference() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("nav_bar_glow_enabled", 99)
        preferences.edit().putBoolean(otherProfileKey, false).commit()
        ThemeSettingsStorage.saveNavBarGlowEnabled(false)

        ThemeSettingsStorage.replaceFromSyncPayload(buildJsonObject {})

        assertNull(ThemeSettingsStorage.loadNavBarGlowEnabled())
        assertEquals(false, preferences.getBoolean(otherProfileKey, true))
    }

    @Test
    fun savedGradientRoundTripsThroughProfileSync() {
        val colors = CustomThemeColors(0x123456, 0xABCDEF, 0x654321).encode()
        ThemeSettingsStorage.saveCustomThemeColors(colors)
        ThemeSettingsStorage.saveSelectedTheme("CUSTOM")
        val payload = ThemeSettingsStorage.exportToSyncPayload()

        assertEquals(colors, payload.decodeSyncString("custom_theme_colors"))
        assertEquals("CUSTOM", payload.decodeSyncString("selected_theme"))

        ThemeSettingsStorage.saveCustomThemeColors(CustomThemeColors.Default.encode())
        ThemeSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(colors, ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals("CUSTOM", ThemeSettingsStorage.loadSelectedTheme())
    }

    @Test
    fun choosingAnotherThemeKeepsTheSavedGradient() {
        val colors = CustomThemeColors(0x112233, 0x445566, 0x778899).encode()
        ThemeSettingsStorage.saveCustomThemeColors(colors)
        ThemeSettingsStorage.saveSelectedTheme("CUSTOM")
        ThemeSettingsStorage.saveSelectedTheme("WHITE")

        assertEquals(colors, ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals(colors, ThemeSettingsStorage.exportToSyncPayload().decodeSyncString("custom_theme_colors"))
    }

    @Test
    fun replacingAnOlderPayloadClearsOnlyTheCurrentProfilesGradient() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("custom_theme_colors", 99)
        preferences.edit().putString(otherProfileKey, "#111111,#222222,#333333").commit()
        ThemeSettingsStorage.saveCustomThemeColors(CustomThemeColors.Default.encode())

        ThemeSettingsStorage.replaceFromSyncPayload(buildJsonObject {
            put("selected_theme", encodeSyncString("WHITE"))
        })

        assertNull(ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals("WHITE", ThemeSettingsStorage.loadSelectedTheme())
        assertEquals("#111111,#222222,#333333", preferences.getString(otherProfileKey, null))
    }

    @Test
    fun navigationGlassAndIconColourRoundTripThroughProfileSync() {
        val barDefault = com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults.floatingBar
        val lensDefault = com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults.selectionLens
        val bar = barDefault.copy(blurRadiusDp = 7.5f)
        val lens = lensDefault.copy(refractionAmountDp = 17f, affectTabIcons = false)
        val encodedBar = com.nuvio.app.core.ui.glass.encodeLiquidGlassEffectSettings(
            bar,
            barDefault,
            com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults.floatingBarLimits,
        )
        val encodedLens = com.nuvio.app.core.ui.glass.encodeLiquidGlassEffectSettings(
            lens,
            lensDefault,
            com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults.selectionLensLimits,
        )

        ThemeSettingsStorage.saveNavBarLiquidGlassEnabled(true)
        ThemeSettingsStorage.saveNavBarLiquidGlassBarEffects(encodedBar)
        ThemeSettingsStorage.saveNavBarLiquidGlassSelectionEffects(encodedLens)
        ThemeSettingsStorage.saveNavBarDefaultIconColorEnabled(true)
        ThemeSettingsStorage.saveNavBarDefaultIconColor("#EC70A9")

        val payload = ThemeSettingsStorage.exportToSyncPayload()
        assertEquals(true, payload.decodeSyncBoolean("nav_bar_liquid_glass_enabled"))
        assertEquals(encodedBar, payload.decodeSyncString("nav_bar_liquid_glass_bar_effects"))
        assertEquals(encodedLens, payload.decodeSyncString("nav_bar_liquid_glass_selection_effects"))
        assertEquals(true, payload.decodeSyncBoolean("nav_bar_default_icon_color_enabled"))
        assertEquals("#EC70A9", payload.decodeSyncString("nav_bar_default_icon_color"))

        ThemeSettingsStorage.saveNavBarLiquidGlassEnabled(false)
        ThemeSettingsStorage.saveNavBarLiquidGlassBarEffects("")
        ThemeSettingsStorage.saveNavBarLiquidGlassSelectionEffects("")
        ThemeSettingsStorage.saveNavBarDefaultIconColorEnabled(false)
        ThemeSettingsStorage.saveNavBarDefaultIconColor("#969CA3")
        ThemeSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(true, ThemeSettingsStorage.loadNavBarLiquidGlassEnabled())
        assertEquals(encodedBar, ThemeSettingsStorage.loadNavBarLiquidGlassBarEffects())
        assertEquals(encodedLens, ThemeSettingsStorage.loadNavBarLiquidGlassSelectionEffects())
        assertEquals(true, ThemeSettingsStorage.loadNavBarDefaultIconColorEnabled())
        assertEquals("#EC70A9", ThemeSettingsStorage.loadNavBarDefaultIconColor())
    }

    @Test
    fun olderPayloadClearsOnlyCurrentProfileNavigationGlassPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE)
        val otherProfileEnabled = ProfileScopedKey.of("nav_bar_liquid_glass_enabled", 99)
        val otherProfileColor = ProfileScopedKey.of("nav_bar_default_icon_color", 99)
        preferences.edit()
            .putBoolean(otherProfileEnabled, true)
            .putString(otherProfileColor, "#123456")
            .commit()
        ThemeSettingsStorage.saveNavBarLiquidGlassEnabled(true)
        ThemeSettingsStorage.saveNavBarDefaultIconColor("#654321")

        ThemeSettingsStorage.replaceFromSyncPayload(buildJsonObject {})

        assertNull(ThemeSettingsStorage.loadNavBarLiquidGlassEnabled())
        assertNull(ThemeSettingsStorage.loadNavBarDefaultIconColor())
        assertEquals(true, preferences.getBoolean(otherProfileEnabled, false))
        assertEquals("#123456", preferences.getString(otherProfileColor, null))
    }

}
