package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.core.ui.glass.LiquidGlassSurfacesSettings
import com.nuvio.app.core.ui.glass.LocalLiquidGlassBackdrop
import com.nuvio.app.core.ui.glass.liquidGlassForSurface
import com.nuvio.app.core.ui.glass.liquidGlassIconTint
import com.nuvio.app.core.ui.glass.nuvioBackdropSource
import com.nuvio.app.core.ui.glass.rememberNuvioBackdrop
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_corner_radius
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_blur_radius
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_refraction_height
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_refraction_amount
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_tint_opacity
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_chromatic_aberration
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_chromatic_aberration_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_depth_effect
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_depth_effect_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_custom_icon_color
import nuvio.composeapp.generated.resources.settings_liquid_glass_custom_icon_color_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_customize
import nuvio.composeapp.generated.resources.settings_liquid_glass_customize_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_global_note
import nuvio.composeapp.generated.resources.settings_liquid_glass_reset
import nuvio.composeapp.generated.resources.settings_liquid_glass_done
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_player
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_stream_selection
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_details
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_search
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_library
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_settings
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun LiquidGlassSurfaceSettingsDrafts(
    content: @Composable (LiquidGlassSurfacesSettings) -> Unit,
) {
    val settings by ThemeSettingsRepository.liquidGlassSurfaceSettings.collectAsStateWithLifecycle()
    content(settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiquidGlassSurfacesSettingsSheet(
    isTablet: Boolean,
    settings: LiquidGlassSurfacesSettings,
    onSurfaceChanged: (LiquidGlassSurface, LiquidGlassSurfaceOverrides) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        // Persist before starting the dismiss animation, which can dispose the sheet immediately.
        onSave()
        scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = if (isTablet) 24.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_liquid_glass_customize),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.settings_liquid_glass_customize_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.settings_liquid_glass_global_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(LiquidGlassSurface.entries, key = { it.name }) { surface ->
                LiquidGlassSurfaceSettingsCard(
                    surface = surface,
                    overrides = settings.forSurface(surface),
                    isTablet = isTablet,
                    onChanged = { onSurfaceChanged(surface, it) },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 24.dp else 16.dp)
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = dismiss) {
                        Text(stringResource(Res.string.settings_liquid_glass_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassSurfaceSettingsCard(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    onChanged: (LiquidGlassSurfaceOverrides) -> Unit,
) {
    val enabled = overrides.enabled ?: surface.defaultEnabled
    val recipe = remember(surface) { surfacePreviewRecipe(surface) }
    val title = stringResource(
        when (surface) {
            LiquidGlassSurface.PLAYER -> Res.string.settings_liquid_glass_surface_player
            LiquidGlassSurface.STREAM_SELECTION -> Res.string.settings_liquid_glass_surface_stream_selection
            LiquidGlassSurface.DETAILS -> Res.string.settings_liquid_glass_surface_details
            LiquidGlassSurface.SEARCH -> Res.string.settings_liquid_glass_surface_search
            LiquidGlassSurface.LIBRARY -> Res.string.settings_liquid_glass_surface_library
            LiquidGlassSurface.SETTINGS -> Res.string.settings_liquid_glass_surface_settings
        },
    )
    val horizontalPadding = if (isTablet) 24.dp else 16.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.settings_liquid_glass_surface_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = { onChanged(overrides.copy(enabled = it)) },
                )
            }

            LiquidGlassSurfacePreview(
                surface = surface,
                recipe = recipe,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                        value = overrides.cornerRadiusPercent ?: recipe.cornerRadiusPercent,
                        valueRange = 0f..50f,
                        valueLabel = "${(overrides.cornerRadiusPercent ?: recipe.cornerRadiusPercent).roundToInt()}%",
                        isTablet = isTablet,
                        steps = 49,
                        onValueChange = { onChanged(overrides.copy(cornerRadiusPercent = it)) },
                    )
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                        value = overrides.blurRadiusDp ?: recipe.blurRadiusDp,
                        valueRange = 0f..32f,
                        valueLabel = "${(overrides.blurRadiusDp ?: recipe.blurRadiusDp).roundToInt()} dp",
                        isTablet = isTablet,
                        steps = 31,
                        onValueChange = { onChanged(overrides.copy(blurRadiusDp = it)) },
                    )
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                        value = overrides.refractionHeightDp ?: recipe.refractionHeightDp,
                        valueRange = 0f..48f,
                        valueLabel = "${(overrides.refractionHeightDp ?: recipe.refractionHeightDp).roundToInt()} dp",
                        isTablet = isTablet,
                        steps = 47,
                        onValueChange = { onChanged(overrides.copy(refractionHeightDp = it)) },
                    )
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                        value = overrides.refractionAmountDp ?: recipe.refractionAmountDp,
                        valueRange = 0f..48f,
                        valueLabel = "${(overrides.refractionAmountDp ?: recipe.refractionAmountDp).roundToInt()} dp",
                        isTablet = isTablet,
                        steps = 47,
                        onValueChange = { onChanged(overrides.copy(refractionAmountDp = it)) },
                    )
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                        value = overrides.tintOpacity ?: recipe.tintOpacity,
                        valueRange = 0f..1f,
                        valueLabel = "${((overrides.tintOpacity ?: recipe.tintOpacity) * 100f).roundToInt()}%",
                        isTablet = isTablet,
                        steps = 19,
                        onValueChange = { onChanged(overrides.copy(tintOpacity = it)) },
                    )
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                        description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                        checked = overrides.chromaticAberration ?: false,
                        isTablet = isTablet,
                        onCheckedChange = { onChanged(overrides.copy(chromaticAberration = it)) },
                    )
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                        description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                        checked = overrides.depthEffect ?: false,
                        isTablet = isTablet,
                        onCheckedChange = { onChanged(overrides.copy(depthEffect = it)) },
                    )
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_liquid_glass_custom_icon_color),
                        description = stringResource(Res.string.settings_liquid_glass_custom_icon_color_description),
                        checked = overrides.defaultIconColorArgb != null,
                        isTablet = isTablet,
                        onCheckedChange = {
                            onChanged(
                                overrides.copy(
                                    defaultIconColorArgb = if (it) {
                                        overrides.defaultIconColorArgb ?: 0xFFB7C6E5.toInt()
                                    } else {
                                        null
                                    },
                                ),
                            )
                        },
                    )
                    overrides.defaultIconColorArgb?.let { color ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ThemeColorPicker(
                                color = color,
                                colorIndex = surface.ordinal,
                                onColorChanged = { onChanged(overrides.copy(defaultIconColorArgb = it)) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onChanged(LiquidGlassSurfaceOverrides()) }) {
                            Text(stringResource(Res.string.settings_liquid_glass_reset))
                        }
                    }
                }
            }
        }
    }
    NuvioBottomSheetDivider()
}

@Composable
private fun LiquidGlassSurfacePreview(
    surface: LiquidGlassSurface,
    recipe: LiquidGlassSurfaceRecipe,
    modifier: Modifier = Modifier,
) {
    val backdrop = rememberNuvioBackdrop()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF22344D),
                            Color(0xFF536C89),
                            Color(0xFF332F4F),
                        ),
                    ),
                )
                .nuvioBackdropSource(backdrop),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (index % 2 == 0) 0.16f else 0.07f)),
                )
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalLiquidGlassBackdrop provides backdrop) {
            Row(
                modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SurfacePreviewIcon(surface, Icons.Rounded.PlayArrow, CircleShape, recipe)
                SurfacePreviewIcon(surface, Icons.Rounded.Search, RoundedCornerShape(16.dp), recipe)
                SurfacePreviewIcon(surface, Icons.Rounded.Settings, RoundedCornerShape(16.dp), recipe)
                SurfacePreviewIcon(surface, Icons.Rounded.Close, CircleShape, recipe)
            }
        }
    }
}

@Composable
private fun SurfacePreviewIcon(
    surface: LiquidGlassSurface,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    shape: Shape,
    recipe: LiquidGlassSurfaceRecipe,
) {
    val overrides = com.nuvio.app.core.ui.glass.LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    val resolvedShape = overrides.cornerRadiusPercent?.let {
        RoundedCornerShape(percent = it.roundToInt())
    } ?: shape
    Box(
        modifier = Modifier
            .size(42.dp)
            .liquidGlassForSurface(
                surface = surface,
                shape = resolvedShape,
                tint = Color.White.copy(alpha = recipe.tintOpacity),
                fallback = Color.White.copy(alpha = 0.12f),
                blurRadius = recipe.blurRadiusDp.dp,
                refractionHeight = recipe.refractionHeightDp.dp,
                refractionAmount = recipe.refractionAmountDp.dp,
                withVibrancy = true,
                withHighlight = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = liquidGlassIconTint(surface, Color.White),
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class LiquidGlassSurfaceRecipe(
    val cornerRadiusPercent: Float,
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val tintOpacity: Float,
)

private fun surfacePreviewRecipe(surface: LiquidGlassSurface): LiquidGlassSurfaceRecipe =
    when (surface) {
        LiquidGlassSurface.PLAYER -> LiquidGlassSurfaceRecipe(50f, 6f, 8f, 12f, 0.08f)
        LiquidGlassSurface.STREAM_SELECTION -> LiquidGlassSurfaceRecipe(50f, 14f, 8f, 12f, 0.28f)
        LiquidGlassSurface.DETAILS -> LiquidGlassSurfaceRecipe(40f, 16f, 10f, 16f, 0.30f)
        LiquidGlassSurface.SEARCH -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
        LiquidGlassSurface.LIBRARY -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
        LiquidGlassSurface.SETTINGS -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
    }

/** The preview shape follows the current live override; null continues to use this surface's recipe. */
