package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.core.ui.parseHexColor
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.isLiquidGlassAvailable
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

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
    val savedBarEffects by ThemeSettingsRepository.navBarLiquidGlassBarEffects.collectAsStateWithLifecycle()
    val savedLensEffects by ThemeSettingsRepository.navBarLiquidGlassSelectionEffects.collectAsStateWithLifecycle()
    val savedIconColorEnabled by ThemeSettingsRepository.navBarDefaultIconColorEnabled.collectAsStateWithLifecycle()
    val savedIconColor by ThemeSettingsRepository.navBarDefaultIconColor.collectAsStateWithLifecycle()
    var draftBarEffects by remember(savedBarEffects) { mutableStateOf(savedBarEffects) }
    var draftLensEffects by remember(savedLensEffects) { mutableStateOf(savedLensEffects) }
    var draftIconColorEnabled by remember(savedIconColorEnabled) { mutableStateOf(savedIconColorEnabled) }
    var draftIconColorText by remember(savedIconColor) { mutableStateOf(formatHexColor(savedIconColor)) }
    val parsedIconColor = parseHexColor(draftIconColorText)
    val liquidGlassSupported = floatingNavigationGlowSupported &&
        isLiquidGlassAvailable() && isLiquidRefractionSupported()

    fun commitDrafts() {
        ThemeSettingsRepository.setNavBarLiquidGlassBarEffects(draftBarEffects)
        ThemeSettingsRepository.setNavBarLiquidGlassSelectionEffects(draftLensEffects)
        if (!draftIconColorEnabled) {
            ThemeSettingsRepository.setNavBarDefaultIconColorEnabled(false)
        } else if (parsedIconColor != null) {
            ThemeSettingsRepository.setNavBarDefaultIconColor(parsedIconColor)
            ThemeSettingsRepository.setNavBarDefaultIconColorEnabled(true)
        }
    }

    val dismiss: () -> Unit = {
        commitDrafts()
        scope.launch { com.nuvio.app.core.ui.dismissNuvioBottomSheet(sheetState, onDismiss) }
    }
    val defaultIconColorOverride = if (draftIconColorEnabled && parsedIconColor != null) {
        Color(parsedIconColor or 0xFF000000.toInt())
    } else {
        null
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
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
                    liquidGlassEnabled = liquidGlassEnabled && liquidGlassSupported && selectedStyle != NavBarStyle.CLASSIC,
                    floatingBarEffects = draftBarEffects,
                    selectionLensEffects = draftLensEffects,
                    defaultIconColorOverride = defaultIconColorOverride,
                )
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
                AnimatedVisibility(selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass),
                            description = stringResource(
                                if (liquidGlassSupported) Res.string.settings_nav_bar_liquid_glass_description
                                else Res.string.settings_nav_bar_liquid_glass_unsupported,
                            ),
                            checked = liquidGlassEnabled,
                            enabled = liquidGlassSupported,
                            isTablet = isTablet,
                            onCheckedChange = ThemeSettingsRepository::setNavBarLiquidGlassEnabled,
                        )
                        AnimatedVisibility(liquidGlassEnabled && liquidGlassSupported) {
                            Column {
                                Text(
                                    stringResource(Res.string.settings_nav_bar_liquid_glass_bar_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_corner_radius),
                                    value = draftBarEffects.cornerRadiusPercent,
                                    valueLabel = "${draftBarEffects.cornerRadiusPercent.roundToInt()}%",
                                    range = 0f..50f,
                                    steps = 49,
                                    onValueChange = { draftBarEffects = draftBarEffects.copy(cornerRadiusPercent = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_blur),
                                    value = draftBarEffects.blurRadiusDp,
                                    valueLabel = "${draftBarEffects.blurRadiusDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxBlurRadiusDp,
                                    steps = 31,
                                    onValueChange = { draftBarEffects = draftBarEffects.copy(blurRadiusDp = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_refraction_height),
                                    value = draftBarEffects.refractionHeightDp,
                                    valueLabel = "${draftBarEffects.refractionHeightDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionHeightDp,
                                    steps = 47,
                                    onValueChange = { draftBarEffects = draftBarEffects.copy(refractionHeightDp = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_refraction_amount),
                                    value = draftBarEffects.refractionAmountDp,
                                    valueLabel = "${draftBarEffects.refractionAmountDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionAmountDp,
                                    steps = 47,
                                    onValueChange = { draftBarEffects = draftBarEffects.copy(refractionAmountDp = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_chromatic_aberration),
                                    checked = draftBarEffects.chromaticAberration,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftBarEffects = draftBarEffects.copy(chromaticAberration = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_depth),
                                    checked = draftBarEffects.depthEffect,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftBarEffects = draftBarEffects.copy(depthEffect = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_tint),
                                    value = draftBarEffects.tintOpacity,
                                    valueLabel = "${(draftBarEffects.tintOpacity * 100).roundToInt()}%",
                                    range = 0f..1f,
                                    steps = 99,
                                    onValueChange = { draftBarEffects = draftBarEffects.copy(tintOpacity = it) },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                                ) {
                                    TextButton(onClick = { draftBarEffects = LiquidGlassEffectDefaults.floatingBar }) {
                                        Text(stringResource(Res.string.action_reset))
                                    }
                                }
                                NuvioBottomSheetDivider()
                                Text(
                                    stringResource(Res.string.settings_nav_bar_liquid_glass_lens_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_corner_radius),
                                    value = draftLensEffects.cornerRadiusPercent,
                                    valueLabel = "${draftLensEffects.cornerRadiusPercent.roundToInt()}%",
                                    range = 0f..50f,
                                    steps = 49,
                                    onValueChange = { draftLensEffects = draftLensEffects.copy(cornerRadiusPercent = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_blur),
                                    value = draftLensEffects.blurRadiusDp,
                                    valueLabel = "${draftLensEffects.blurRadiusDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxBlurRadiusDp,
                                    steps = 23,
                                    onValueChange = { draftLensEffects = draftLensEffects.copy(blurRadiusDp = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_refraction_height),
                                    value = draftLensEffects.refractionHeightDp,
                                    valueLabel = "${draftLensEffects.refractionHeightDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionHeightDp,
                                    steps = 31,
                                    onValueChange = { draftLensEffects = draftLensEffects.copy(refractionHeightDp = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_refraction_amount),
                                    value = draftLensEffects.refractionAmountDp,
                                    valueLabel = "${draftLensEffects.refractionAmountDp.roundToInt()} dp",
                                    range = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionAmountDp,
                                    steps = 31,
                                    onValueChange = { draftLensEffects = draftLensEffects.copy(refractionAmountDp = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_chromatic_aberration),
                                    checked = draftLensEffects.chromaticAberration,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftLensEffects = draftLensEffects.copy(chromaticAberration = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_depth),
                                    checked = draftLensEffects.depthEffect,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftLensEffects = draftLensEffects.copy(depthEffect = it) },
                                )
                                LiquidGlassSliderRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_tint),
                                    value = draftLensEffects.tintOpacity,
                                    valueLabel = "${(draftLensEffects.tintOpacity * 100).roundToInt()}%",
                                    range = 0f..1f,
                                    steps = 99,
                                    onValueChange = { draftLensEffects = draftLensEffects.copy(tintOpacity = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_affect_tab_icons),
                                    description = stringResource(Res.string.settings_nav_bar_liquid_glass_affect_tab_icons_description),
                                    checked = draftLensEffects.affectTabIcons,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftLensEffects = draftLensEffects.copy(affectTabIcons = it) },
                                )
                                SettingsSwitchRow(
                                    title = stringResource(Res.string.settings_nav_bar_liquid_glass_affect_floating_bar),
                                    description = stringResource(Res.string.settings_nav_bar_liquid_glass_affect_floating_bar_description),
                                    checked = draftLensEffects.affectFloatingBar,
                                    isTablet = isTablet,
                                    onCheckedChange = { draftLensEffects = draftLensEffects.copy(affectFloatingBar = it) },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                                ) {
                                    TextButton(onClick = { draftLensEffects = LiquidGlassEffectDefaults.selectionLens }) {
                                        Text(stringResource(Res.string.action_reset))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                NuvioBottomSheetDivider()
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_nav_bar_inactive_icon_color),
                    description = stringResource(Res.string.settings_nav_bar_inactive_icon_color_description),
                    checked = draftIconColorEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { draftIconColorEnabled = it },
                )
                AnimatedVisibility(draftIconColorEnabled) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = draftIconColorText,
                            onValueChange = { if (it.length <= 7) draftIconColorText = it },
                            label = { Text(stringResource(Res.string.settings_nav_bar_inactive_icon_color_hex)) },
                            singleLine = true,
                            isError = parsedIconColor == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (parsedIconColor == null) {
                            Text(
                                text = stringResource(Res.string.settings_nav_bar_inactive_icon_color_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
private fun LiquidGlassSliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
