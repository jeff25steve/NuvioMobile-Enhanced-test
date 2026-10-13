package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import com.kyant.backdrop.isRuntimeShaderSupported
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassEffectSettings
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationBarSettingsSheet(
    isTablet: Boolean,
    tabletLayout: Boolean = false,
    selectedStyle: NavBarStyle,
    onStyleSelected: (NavBarStyle) -> Unit,
    glowEnabled: Boolean,
    onGlowChanged: (Boolean) -> Unit,
    liquidGlassEnabled: Boolean,
    onLiquidGlassChanged: (Boolean) -> Unit,
    floatingBarEffects: LiquidGlassEffectSettings,
    onFloatingBarEffectsChanged: (LiquidGlassEffectSettings) -> Unit,
    selectionLensEffects: LiquidGlassEffectSettings,
    onSelectionLensEffectsChanged: (LiquidGlassEffectSettings) -> Unit,
    onSaveLiquidGlassEffectSettings: () -> Unit,
    selectedPosition: NavBarPosition,
    onPositionSelected: (NavBarPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    val liquidGlassSupported = isRuntimeShaderSupported()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch {
            onSaveLiquidGlassEffectSettings()
            dismissNuvioBottomSheet(sheetState, onDismiss)
        }
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
                    liquidGlassEnabled = liquidGlassEnabled,
                    position = selectedPosition,
                    tabletLayout = tabletLayout,
                    floatingBarEffects = floatingBarEffects,
                    selectionLensEffects = selectionLensEffects,
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
                // Only the floating pill can move; CLASSIC keeps its fixed place.
                AnimatedVisibility(selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        Text(
                            text = stringResource(Res.string.settings_nav_bar_position),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                        NavBarPosition.entries.forEach { position ->
                            NuvioBottomSheetActionRow(
                                title = stringResource(position.labelRes),
                                onClick = { onPositionSelected(position) },
                                modifier = Modifier.semantics {
                                    role = Role.RadioButton
                                    selected = position == selectedPosition
                                },
                                trailingContent = {
                                    if (position == selectedPosition) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                            )
                        }
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
                                if (liquidGlassSupported) {
                                    Res.string.settings_nav_bar_liquid_glass_description
                                } else {
                                    Res.string.settings_nav_bar_liquid_glass_unsupported_description
                                },
                            ),
                            checked = liquidGlassEnabled,
                            enabled = liquidGlassSupported,
                            isTablet = isTablet,
                            onCheckedChange = onLiquidGlassChanged,
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = selectedStyle != NavBarStyle.CLASSIC && liquidGlassSupported && liquidGlassEnabled,
                ) {
                    Column {
                        NuvioBottomSheetDivider()
                        LiquidGlassEffectGroupHeader(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass_floating_bar),
                            resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_bar),
                            isTablet = isTablet,
                            onReset = {
                                onFloatingBarEffectsChanged(LiquidGlassEffectDefaults.floatingBar)
                            },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                            value = floatingBarEffects.cornerRadiusPercent,
                            valueRange = 0f..50f,
                            steps = 49,
                            valueLabel = "${floatingBarEffects.cornerRadiusPercent.roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(cornerRadiusPercent = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                            value = floatingBarEffects.blurRadiusDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxBlurRadiusDp,
                            steps = 31,
                            valueLabel = "${floatingBarEffects.blurRadiusDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(blurRadiusDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                            value = floatingBarEffects.refractionHeightDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionHeightDp,
                            steps = 47,
                            valueLabel = "${floatingBarEffects.refractionHeightDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(refractionHeightDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                            value = floatingBarEffects.refractionAmountDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionAmountDp,
                            steps = 47,
                            valueLabel = "${floatingBarEffects.refractionAmountDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(refractionAmountDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                            value = floatingBarEffects.tintOpacity,
                            valueRange = 0f..1f,
                            steps = 19,
                            valueLabel = "${(floatingBarEffects.tintOpacity * 100f).roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(tintOpacity = it)) },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                            description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                            checked = floatingBarEffects.chromaticAberration,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onFloatingBarEffectsChanged(floatingBarEffects.copy(chromaticAberration = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                            description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                            checked = floatingBarEffects.depthEffect,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onFloatingBarEffectsChanged(floatingBarEffects.copy(depthEffect = it))
                            },
                        )

                        NuvioBottomSheetDivider()
                        LiquidGlassEffectGroupHeader(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass_selection_lens),
                            resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_lens),
                            isTablet = isTablet,
                            onReset = {
                                onSelectionLensEffectsChanged(LiquidGlassEffectDefaults.selectionLens)
                            },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                            value = selectionLensEffects.cornerRadiusPercent,
                            valueRange = 0f..50f,
                            steps = 49,
                            valueLabel = "${selectionLensEffects.cornerRadiusPercent.roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(cornerRadiusPercent = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                            value = selectionLensEffects.blurRadiusDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxBlurRadiusDp,
                            steps = 23,
                            valueLabel = "${selectionLensEffects.blurRadiusDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(blurRadiusDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                            value = selectionLensEffects.refractionHeightDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionHeightDp,
                            steps = 31,
                            valueLabel = "${selectionLensEffects.refractionHeightDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(refractionHeightDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                            value = selectionLensEffects.refractionAmountDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionAmountDp,
                            steps = 31,
                            valueLabel = "${selectionLensEffects.refractionAmountDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(refractionAmountDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                            value = selectionLensEffects.tintOpacity,
                            valueRange = 0f..1f,
                            steps = 19,
                            valueLabel = "${(selectionLensEffects.tintOpacity * 100f).roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(tintOpacity = it)) },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                            description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                            checked = selectionLensEffects.chromaticAberration,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(chromaticAberration = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                            description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                            checked = selectionLensEffects.depthEffect,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(depthEffect = it))
                            },
                        )
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
            .padding(
                start = if (isTablet) 20.dp else 16.dp,
                end = 8.dp,
                top = 12.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = resetDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LiquidGlassEffectSlider(
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
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = 2.dp,
            ),
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
