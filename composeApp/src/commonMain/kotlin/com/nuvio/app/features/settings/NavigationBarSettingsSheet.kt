package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.core.ui.parseHexColor
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassEffectSettings
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isIos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Keep slider subscriptions and their high-frequency updates inside the effect editor.
 * The open sheet and the settings item behind it do not need to recompose on each drag frame.
 */
@Composable
internal fun NavigationBarLiquidGlassEffectDrafts(
    content: @Composable (LiquidGlassEffectSettings, LiquidGlassEffectSettings) -> Unit,
) {
    val barEffects by ThemeSettingsRepository.navBarLiquidGlassBarEffects.collectAsStateWithLifecycle()
    val selectionEffects by ThemeSettingsRepository.navBarLiquidGlassSelectionEffects.collectAsStateWithLifecycle()
    content(barEffects, selectionEffects)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationBarSettingsSheet(
    isTablet: Boolean,
    selectedStyle: NavBarStyle,
    onStyleSelected: (NavBarStyle) -> Unit,
    glowEnabled: Boolean,
    onGlowChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val liquidGlassEnabled by ThemeSettingsRepository.navBarLiquidGlassEnabled.collectAsStateWithLifecycle()
    val defaultIconColorEnabled by ThemeSettingsRepository.navBarDefaultIconColorEnabled.collectAsStateWithLifecycle()
    val defaultIconColor by ThemeSettingsRepository.navBarDefaultIconColor.collectAsStateWithLifecycle()
    var draftIconColorEnabled by remember(defaultIconColorEnabled) { mutableStateOf(defaultIconColorEnabled) }
    var defaultIconColorExpanded by rememberSaveable { mutableStateOf(false) }
    val liquidGlassSupported = floatingNavigationGlowSupported &&
        com.nuvio.app.core.ui.glass.isLiquidGlassAvailable() &&
        com.nuvio.app.core.ui.glass.isLiquidRefractionSupported()

    fun commitDrafts() {
        ThemeSettingsRepository.setNavBarDefaultIconColorEnabled(draftIconColorEnabled)
        ThemeSettingsRepository.saveNavBarDefaultIconColorSettings()
        ThemeSettingsRepository.saveNavBarLiquidGlassEffectSettings()
    }

    val dismiss: () -> Unit = {
        // Commit before starting the suspend/animated dismissal so Done, Back and outside-tap
        // dismissal all preserve the user's last valid drafts.
        commitDrafts()
        scope.launch {
            com.nuvio.app.core.ui.dismissNuvioBottomSheet(sheetState, onDismiss)
        }
    }
    val defaultIconColorOverride = if (draftIconColorEnabled) {
        Color(defaultIconColor or 0xFF000000.toInt())
    } else {
        null
    }
    val listState = rememberLazyListState()
    var bounceTrigger by remember { mutableIntStateOf(0) }
    var bounceDirection by remember { mutableStateOf(0f) }

    LaunchedEffect(listState) {
        val gate = NavigationPreviewScrollBounceGate()
        snapshotFlow { listState.canScrollBackward }
            .distinctUntilChanged()
            .collectLatest { canScrollBackward ->
                val direction = gate.onScrollabilityChanged(canScrollBackward) ?: return@collectLatest
                bounceDirection = direction
                bounceTrigger = if (bounceTrigger == Int.MAX_VALUE) 1 else bounceTrigger + 1
            }
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            stickyHeader(key = "navigation-bar-preview") {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.nuvio.colors.surfaceSheet),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_appearance_nav_bar_style),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    NavigationBarPreview(
                        style = selectedStyle,
                        isTablet = isTablet,
                        glowEnabled = glowEnabled,
                        liquidGlassEnabled = liquidGlassEnabled && liquidGlassSupported && (isTablet || selectedStyle != NavBarStyle.CLASSIC),
                        defaultIconColorOverride = defaultIconColorOverride,
                        scrollBounceTrigger = bounceTrigger,
                        scrollBounceDirection = bounceDirection,
                    )
                }
            }
            if (!isTablet) {
                NavBarStyle.entries.forEach { style ->
                    item(key = style.key) {
                        NuvioBottomSheetActionRow(
                            title = stringResource(style.labelRes),
                            onClick = { onStyleSelected(style) },
                            selected = style == selectedStyle,
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = style == selectedStyle
                            },
                            trailingContent = {
                                if (style == selectedStyle) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(floatingNavigationGlowSupported && selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glow),
                            description = stringResource(Res.string.settings_nav_bar_glow_description),
                            checked = glowEnabled,
                            isTablet = isTablet,
                            onCheckedChange = onGlowChanged,
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(isTablet || selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass),
                            description = stringResource(
                                if (liquidGlassSupported) Res.string.settings_nav_bar_liquid_glass_description
                                else Res.string.settings_nav_bar_liquid_glass_unsupported_description,
                            ),
                            checked = liquidGlassEnabled,
                            enabled = liquidGlassSupported,
                            isTablet = isTablet,
                            onCheckedChange = ThemeSettingsRepository::setNavBarLiquidGlassEnabled,
                        )
                        AnimatedVisibility(liquidGlassEnabled && liquidGlassSupported) {
                            NavigationBarLiquidGlassEffectDrafts { barEffects, lensEffects ->
                                Column {
                                    NuvioBottomSheetDivider()
                                    LiquidGlassEffectGroupHeader(
                                        title = stringResource(Res.string.settings_nav_bar_liquid_glass_floating_bar),
                                        resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_bar),
                                        isTablet = isTablet,
                                        onReset = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                LiquidGlassEffectDefaults.floatingBar,
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                                        value = barEffects.cornerRadiusPercent,
                                        valueLabel = "${barEffects.cornerRadiusPercent.roundToInt()}%",
                                        valueRange = 0f..50f,
                                        steps = 49,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(cornerRadiusPercent = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                                        value = barEffects.blurRadiusDp,
                                        valueLabel = "${barEffects.blurRadiusDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxBlurRadiusDp,
                                        steps = 31,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(blurRadiusDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                                        value = barEffects.refractionHeightDp,
                                        valueLabel = "${barEffects.refractionHeightDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionHeightDp,
                                        steps = 47,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(refractionHeightDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                                        value = barEffects.refractionAmountDp,
                                        valueLabel = "${barEffects.refractionAmountDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionAmountDp,
                                        steps = 47,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(refractionAmountDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                                        value = barEffects.tintOpacity,
                                        valueLabel = "${(barEffects.tintOpacity * 100f).roundToInt()}%",
                                        valueRange = 0f..1f,
                                        steps = 19,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(tintOpacity = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                                        description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                                        checked = barEffects.chromaticAberration,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(chromaticAberration = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                                        description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                                        checked = barEffects.depthEffect,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassBarEffects(
                                                barEffects.copy(depthEffect = it),
                                            )
                                        },
                                    )
                                    NuvioBottomSheetDivider()
                                    LiquidGlassEffectGroupHeader(
                                        title = stringResource(Res.string.settings_nav_bar_liquid_glass_selection_lens),
                                        resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_lens),
                                        isTablet = isTablet,
                                        onReset = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                LiquidGlassEffectDefaults.selectionLens,
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                                        value = lensEffects.cornerRadiusPercent,
                                        valueLabel = "${lensEffects.cornerRadiusPercent.roundToInt()}%",
                                        valueRange = 0f..50f,
                                        steps = 49,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(cornerRadiusPercent = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                                        value = lensEffects.blurRadiusDp,
                                        valueLabel = "${lensEffects.blurRadiusDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxBlurRadiusDp,
                                        steps = 23,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(blurRadiusDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                                        value = lensEffects.refractionHeightDp,
                                        valueLabel = "${lensEffects.refractionHeightDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionHeightDp,
                                        steps = 31,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(refractionHeightDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                                        value = lensEffects.refractionAmountDp,
                                        valueLabel = "${lensEffects.refractionAmountDp.roundToInt()} dp",
                                        valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionAmountDp,
                                        steps = 31,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(refractionAmountDp = it),
                                            )
                                        },
                                    )
                                    LiquidGlassEffectSlider(
                                        title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                                        value = lensEffects.tintOpacity,
                                        valueLabel = "${(lensEffects.tintOpacity * 100f).roundToInt()}%",
                                        valueRange = 0f..1f,
                                        steps = 19,
                                        isTablet = isTablet,
                                        onValueChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(tintOpacity = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                                        description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                                        checked = lensEffects.chromaticAberration,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(chromaticAberration = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                                        description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                                        checked = lensEffects.depthEffect,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(depthEffect = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_lens_affect_tab_icons),
                                        description = stringResource(Res.string.settings_nav_bar_glass_lens_affect_tab_icons_description),
                                        checked = lensEffects.affectTabIcons,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(affectTabIcons = it),
                                            )
                                        },
                                    )
                                    SettingsSwitchRow(
                                        title = stringResource(Res.string.settings_nav_bar_glass_lens_affect_floating_bar),
                                        description = stringResource(Res.string.settings_nav_bar_glass_lens_affect_floating_bar_description),
                                        checked = lensEffects.affectFloatingBar,
                                        isTablet = isTablet,
                                        onCheckedChange = {
                                            ThemeSettingsRepository.updateNavBarLiquidGlassSelectionEffects(
                                                lensEffects.copy(affectFloatingBar = it),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(!isIos && (isTablet || selectedStyle != NavBarStyle.CLASSIC)) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_default_icon_color),
                            description = stringResource(Res.string.settings_nav_bar_default_icon_color_description),
                            checked = draftIconColorEnabled,
                            isTablet = isTablet,
                            onCheckedChange = { enabled ->
                                if (!enabled) defaultIconColorExpanded = false
                                draftIconColorEnabled = enabled
                            },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility((isTablet || selectedStyle != NavBarStyle.CLASSIC) && draftIconColorEnabled) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    defaultIconColorExpanded = !defaultIconColorExpanded
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_nav_bar_default_icon_color_change),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (defaultIconColorExpanded) {
                                            Res.string.settings_nav_bar_default_icon_color_collapse
                                        } else {
                                            Res.string.settings_nav_bar_default_icon_color_expand
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        AnimatedVisibility(defaultIconColorExpanded) {
                            NavigationBarIconColorEditor(
                                color = defaultIconColor,
                                isTablet = isTablet,
                                onColorChanged = ThemeSettingsRepository::updateNavBarDefaultIconColor,
                            )
                        }
                        NuvioBottomSheetDivider()
                    }
                }
            }
            item {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.action_done),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    onClick = dismiss,
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassEffectGroupHeader(
    title: String,
    resetDescription: String,
    isTablet: Boolean,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = resetDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LiquidGlassEffectSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    isTablet: Boolean,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    val safeValue = value.takeIf { it.isFinite() }?.coerceIn(valueRange) ?: valueRange.start
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = safeValue,
            onValueChange = { onValueChange(it.coerceIn(valueRange)) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun NavigationBarIconColorEditor(
    color: Int,
    isTablet: Boolean,
    onColorChanged: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var hexCode by remember(color) { mutableStateOf(formatHexColor(color)) }
    LaunchedEffect(color) {
        hexCode = formatHexColor(color)
    }
    val hexLabel = stringResource(Res.string.custom_theme_hex_title)
    val validHex = parseHexColor(hexCode) != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeColorPicker(
            color = color,
            colorIndex = 0,
            onColorChanged = { updated ->
                onColorChanged(updated)
                hexCode = formatHexColor(updated)
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = hexLabel,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary,
            )
            NuvioInputField(
                value = hexCode,
                onValueChange = { value ->
                    hexCode = value
                    parseHexColor(value)?.let(onColorChanged)
                },
                placeholder = "#969CA3",
                modifier = Modifier.semantics { contentDescription = hexLabel },
            )
            if (!validHex) {
                Text(
                    text = stringResource(Res.string.custom_theme_hex_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}
