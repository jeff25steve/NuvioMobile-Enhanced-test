package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.core.ui.parseHexColor
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassEffectSettings
import com.nuvio.app.core.ui.glass.decodeLiquidGlassEffectSettings
import com.nuvio.app.core.ui.glass.encodeLiquidGlassEffectSettings
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.availableAppThemes
import com.nuvio.app.features.membership.resolveAppTheme
import com.nuvio.app.features.membership.resolveCustomThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val DEFAULT_NAV_BAR_ICON_COLOR = 0x969CA3

object ThemeSettingsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _selectedThemePreference = MutableStateFlow<AppTheme?>(null)
    val selectedThemePreference: StateFlow<AppTheme?> = _selectedThemePreference.asStateFlow()
    private val _selectedTheme = MutableStateFlow(AppTheme.WHITE)
    val selectedTheme: StateFlow<AppTheme> = _selectedTheme.asStateFlow()

    private val _customThemePreference = MutableStateFlow(CustomThemeColors.Default)
    val customThemePreference: StateFlow<CustomThemeColors> = _customThemePreference.asStateFlow()
    private val _customThemeColors = MutableStateFlow(CustomThemeColors.solid(CustomThemeColors.Default.second))
    val customThemeColors: StateFlow<CustomThemeColors> = _customThemeColors.asStateFlow()

    private val _amoledEnabled = MutableStateFlow(false)
    val amoledEnabled: StateFlow<Boolean> = _amoledEnabled.asStateFlow()

    private val _liquidGlassNativeTabBarEnabled = MutableStateFlow(false)
    val liquidGlassNativeTabBarEnabled: StateFlow<Boolean> = _liquidGlassNativeTabBarEnabled.asStateFlow()

    private val _selectedAppLanguage = MutableStateFlow(AppLanguage.DEVICE)
    val selectedAppLanguage: StateFlow<AppLanguage> = _selectedAppLanguage.asStateFlow()

    private val _navBarStyle = MutableStateFlow(NavBarStyle.ADAPTIVE)
    val navBarStyle: StateFlow<NavBarStyle> = _navBarStyle.asStateFlow()

    private val _navBarGlowEnabled = MutableStateFlow(true)
    val navBarGlowEnabled: StateFlow<Boolean> = _navBarGlowEnabled.asStateFlow()

    private val _navBarLiquidGlassEnabled = MutableStateFlow(false)
    val navBarLiquidGlassEnabled: StateFlow<Boolean> = _navBarLiquidGlassEnabled.asStateFlow()
    private val _navBarLiquidGlassBarEffects = MutableStateFlow(LiquidGlassEffectDefaults.floatingBar)
    val navBarLiquidGlassBarEffects: StateFlow<LiquidGlassEffectSettings> = _navBarLiquidGlassBarEffects.asStateFlow()
    private val _navBarLiquidGlassSelectionEffects = MutableStateFlow(LiquidGlassEffectDefaults.selectionLens)
    val navBarLiquidGlassSelectionEffects: StateFlow<LiquidGlassEffectSettings> = _navBarLiquidGlassSelectionEffects.asStateFlow()

    private val _navBarDefaultIconColorEnabled = MutableStateFlow(false)
    val navBarDefaultIconColorEnabled: StateFlow<Boolean> = _navBarDefaultIconColorEnabled.asStateFlow()
    private val _navBarDefaultIconColor = MutableStateFlow(DEFAULT_NAV_BAR_ICON_COLOR)
    val navBarDefaultIconColor: StateFlow<Int> = _navBarDefaultIconColor.asStateFlow()

    private var hasLoaded = false
    private var observesMembership = false

    fun ensureLoaded() {
        observeMembership()
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _selectedThemePreference.value = null
        _selectedTheme.value = AppTheme.WHITE
        _customThemePreference.value = CustomThemeColors.Default
        _customThemeColors.value = CustomThemeColors.solid(CustomThemeColors.Default.second)
        _amoledEnabled.value = false
        _liquidGlassNativeTabBarEnabled.value = false
        NativeTabBridge.publishAccentColor(ThemeColors.White.nativeAccentHex)
        NativeTabBridge.publishLiquidGlassEnabled(false)
        _selectedAppLanguage.value = AppLanguage.DEVICE
        _navBarGlowEnabled.value = true
        _navBarLiquidGlassEnabled.value = false
        _navBarLiquidGlassBarEffects.value = LiquidGlassEffectDefaults.floatingBar
        _navBarLiquidGlassSelectionEffects.value = LiquidGlassEffectDefaults.selectionLens
        _navBarDefaultIconColorEnabled.value = false
        _navBarDefaultIconColor.value = DEFAULT_NAV_BAR_ICON_COLOR
        _navBarStyle.value = NavBarStyle.ADAPTIVE
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = ThemeSettingsStorage.loadSelectedTheme()
        val theme = if (stored != null) {
            try {
                AppTheme.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
        _selectedThemePreference.value = theme
        _customThemePreference.value = CustomThemeColors.decode(ThemeSettingsStorage.loadCustomThemeColors())
        applyEffectiveTheme()
        _amoledEnabled.value = ThemeSettingsStorage.loadAmoledEnabled() ?: false
        val liquidGlassEnabled = ThemeSettingsStorage.loadLiquidGlassNativeTabBarEnabled() ?: false
        _liquidGlassNativeTabBarEnabled.value = liquidGlassEnabled
        NativeTabBridge.publishLiquidGlassEnabled(liquidGlassEnabled)
        val appLanguage = AppLanguage.fromCode(ThemeSettingsStorage.loadSelectedAppLanguage())
        ThemeSettingsStorage.applySelectedAppLanguage(appLanguage.code)
        _selectedAppLanguage.value = appLanguage
        _navBarGlowEnabled.value = ThemeSettingsStorage.loadNavBarGlowEnabled() ?: true
        _navBarLiquidGlassEnabled.value = ThemeSettingsStorage.loadNavBarLiquidGlassEnabled() ?: false
        _navBarLiquidGlassBarEffects.value = decodeLiquidGlassEffectSettings(
            ThemeSettingsStorage.loadNavBarLiquidGlassBarEffects(),
            LiquidGlassEffectDefaults.floatingBar,
            LiquidGlassEffectDefaults.floatingBarLimits,
        )
        _navBarLiquidGlassSelectionEffects.value = decodeLiquidGlassEffectSettings(
            ThemeSettingsStorage.loadNavBarLiquidGlassSelectionEffects(),
            LiquidGlassEffectDefaults.selectionLens,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )
        _navBarDefaultIconColorEnabled.value = ThemeSettingsStorage.loadNavBarDefaultIconColorEnabled() ?: false
        _navBarDefaultIconColor.value = ThemeSettingsStorage.loadNavBarDefaultIconColor()
            ?.let(::parseHexColor) ?: DEFAULT_NAV_BAR_ICON_COLOR
        _navBarStyle.value = NavBarStyle.fromKey(ThemeSettingsStorage.loadNavBarStyle())
    }

    fun setTheme(theme: AppTheme) {
        ensureLoaded()
        val access = MemberAccessRepository.access.value
        if (theme !in availableAppThemes(access.entitlements)) return
        if (_selectedThemePreference.value == theme) return
        _selectedThemePreference.value = theme
        ThemeSettingsStorage.saveSelectedTheme(theme.name)
        applyEffectiveTheme()
    }

    fun setCustomTheme(colors: CustomThemeColors) {
        ensureLoaded()
        val access = MemberAccessRepository.access.value
        val selectedColors = resolveCustomThemeColors(colors, access.tier)
        ThemeSettingsStorage.saveCustomThemeColors(selectedColors.encode())
        ThemeSettingsStorage.saveSelectedTheme(AppTheme.CUSTOM.name)
        _customThemePreference.value = selectedColors
        _selectedThemePreference.value = AppTheme.CUSTOM
        applyEffectiveTheme()
    }

    fun setAmoled(enabled: Boolean) {
        ensureLoaded()
        if (_amoledEnabled.value == enabled) return
        _amoledEnabled.value = enabled
        ThemeSettingsStorage.saveAmoledEnabled(enabled)
    }

    fun setLiquidGlassNativeTabBar(enabled: Boolean) {
        ensureLoaded()
        if (_liquidGlassNativeTabBarEnabled.value == enabled) return
        _liquidGlassNativeTabBarEnabled.value = enabled
        ThemeSettingsStorage.saveLiquidGlassNativeTabBarEnabled(enabled)
        NativeTabBridge.publishLiquidGlassEnabled(enabled)
    }

    fun setAppLanguage(language: AppLanguage) {
        ensureLoaded()
        if (_selectedAppLanguage.value == language) return
        ThemeSettingsStorage.saveSelectedAppLanguage(language.code)
        ThemeSettingsStorage.applySelectedAppLanguage(language.code)
        _selectedAppLanguage.value = language
    }

    fun setNavBarStyle(style: NavBarStyle) {
        ensureLoaded()
        if (_navBarStyle.value == style) return
        _navBarStyle.value = style
        ThemeSettingsStorage.saveNavBarStyle(style.key)
    }

    fun setNavBarGlowEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_navBarGlowEnabled.value == enabled) return
        _navBarGlowEnabled.value = enabled
        ThemeSettingsStorage.saveNavBarGlowEnabled(enabled)
    }

    fun setNavBarLiquidGlassEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_navBarLiquidGlassEnabled.value == enabled) return
        _navBarLiquidGlassEnabled.value = enabled
        ThemeSettingsStorage.saveNavBarLiquidGlassEnabled(enabled)
    }

    fun setNavBarLiquidGlassBarEffects(settings: LiquidGlassEffectSettings) {
        ensureLoaded()
        val safe = settings.sanitized(
            LiquidGlassEffectDefaults.floatingBar,
            LiquidGlassEffectDefaults.floatingBarLimits,
        )
        if (_navBarLiquidGlassBarEffects.value == safe) return
        _navBarLiquidGlassBarEffects.value = safe
        ThemeSettingsStorage.saveNavBarLiquidGlassBarEffects(
            encodeLiquidGlassEffectSettings(
                safe,
                LiquidGlassEffectDefaults.floatingBar,
                LiquidGlassEffectDefaults.floatingBarLimits,
            ),
        )
    }

    fun setNavBarLiquidGlassSelectionEffects(settings: LiquidGlassEffectSettings) {
        ensureLoaded()
        val safe = settings.sanitized(
            LiquidGlassEffectDefaults.selectionLens,
            LiquidGlassEffectDefaults.selectionLensLimits,
        )
        if (_navBarLiquidGlassSelectionEffects.value == safe) return
        _navBarLiquidGlassSelectionEffects.value = safe
        ThemeSettingsStorage.saveNavBarLiquidGlassSelectionEffects(
            encodeLiquidGlassEffectSettings(
                safe,
                LiquidGlassEffectDefaults.selectionLens,
                LiquidGlassEffectDefaults.selectionLensLimits,
            ),
        )
    }

    fun setNavBarDefaultIconColorEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_navBarDefaultIconColorEnabled.value == enabled) return
        _navBarDefaultIconColorEnabled.value = enabled
        ThemeSettingsStorage.saveNavBarDefaultIconColorEnabled(enabled)
    }

    fun setNavBarDefaultIconColor(color: Int) {
        ensureLoaded()
        val safeColor = color and 0xFFFFFF
        if (_navBarDefaultIconColor.value == safeColor) return
        _navBarDefaultIconColor.value = safeColor
        ThemeSettingsStorage.saveNavBarDefaultIconColor(formatHexColor(safeColor))
    }

    private fun observeMembership() {
        if (observesMembership) return
        observesMembership = true
        MemberAccessRepository.ensureStarted()
        scope.launch {
            MemberAccessRepository.access.collect {
                if (hasLoaded) applyEffectiveTheme()
            }
        }
    }

    private fun applyEffectiveTheme() {
        val access = MemberAccessRepository.access.value
        val effective = resolveAppTheme(
            selectedTheme = _selectedThemePreference.value,
            entitlements = access.entitlements,
        )
        _customThemeColors.value = resolveCustomThemeColors(_customThemePreference.value, access.tier)
        _selectedTheme.value = effective
        NativeTabBridge.publishAccentColor(
            ThemeColors.getColorPalette(effective, _customThemeColors.value).nativeAccentHex,
        )
    }
}
