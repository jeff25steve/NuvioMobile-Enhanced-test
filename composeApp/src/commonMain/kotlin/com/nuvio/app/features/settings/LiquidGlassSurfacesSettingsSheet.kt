package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nuvio.app.core.ui.usesNativeNuvioBottomSheet
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.core.ui.glass.LiquidGlassSurfacesSettings
import com.nuvio.app.core.ui.glass.LocalLiquidGlassBackdrop
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
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
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_highlight
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_highlight_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_vibrancy
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_vibrancy_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_shadow
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_shadow_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_chromatic_aberration
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_chromatic_aberration_description
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_depth_effect
import nuvio.composeapp.generated.resources.settings_nav_bar_glass_depth_effect_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_custom_icon_color
import nuvio.composeapp.generated.resources.settings_liquid_glass_custom_icon_color_description
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.settings_liquid_glass_customize
import nuvio.composeapp.generated.resources.settings_liquid_glass_customize_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_default
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
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.compose_search_placeholder
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.library_source_cloud
import nuvio.composeapp.generated.resources.library_source_saved
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_bottom_sheets
import nuvio.composeapp.generated.resources.streams_search_placeholder
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
    var selectedSurfaceName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSurface = selectedSurfaceName?.let { name ->
        LiquidGlassSurface.entries.firstOrNull { it.name == name }
    }
    val customizableSurfaces = remember(usesNativeNuvioBottomSheet) {
        if (usesNativeNuvioBottomSheet) {
            LiquidGlassSurface.entries.filter { it != LiquidGlassSurface.BOTTOM_SHEETS }
        } else {
            LiquidGlassSurface.entries
        }
    }
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
            if (selectedSurface == null) {
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
                items(customizableSurfaces, key = { it.name }) { surface ->
                    LiquidGlassSurfaceListItem(
                        surface = surface,
                        overrides = settings.forSurface(surface),
                        isTablet = isTablet,
                        onOpen = { selectedSurfaceName = surface.name },
                        onToggle = { enabled ->
                            onSurfaceChanged(surface, settings.forSurface(surface).copy(enabled = enabled))
                        },
                    )
                }
            } else {
                item {
                    LiquidGlassSurfaceEditor(
                        surface = selectedSurface,
                        overrides = settings.forSurface(selectedSurface),
                        isTablet = isTablet,
                        onBack = { selectedSurfaceName = null },
                        onChanged = { onSurfaceChanged(selectedSurface, it) },
                    )
                }
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
private fun LiquidGlassSurfaceListItem(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val enabled = overrides.enabled ?: surface.defaultEnabled
    val title = liquidGlassSurfaceTitle(surface)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 24.dp else 16.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpen)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                    onCheckedChange = onToggle,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpen) {
                    Text(stringResource(Res.string.settings_liquid_glass_customize))
                }
            }
        }
    }
    NuvioBottomSheetDivider()
}

@Composable
private fun LiquidGlassSurfaceEditor(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    onBack: () -> Unit,
    onChanged: (LiquidGlassSurfaceOverrides) -> Unit,
) {
    val enabled = overrides.enabled ?: surface.defaultEnabled
    val refractionSupported = isLiquidRefractionSupported()
    val recipe = remember(surface) { surfacePreviewRecipe(surface) }
    val title = liquidGlassSurfaceTitle(surface)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_liquid_glass_surface_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LiquidGlassSurfacePreview(
            surface = surface,
            recipe = recipe,
            modifier = Modifier.padding(horizontal = if (isTablet) 24.dp else 16.dp),
        )

        SettingsSwitchRow(
            title = title,
            description = stringResource(Res.string.settings_liquid_glass_surface_description),
            checked = enabled,
            isTablet = isTablet,
            onCheckedChange = { onChanged(overrides.copy(enabled = it)) },
        )

        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_highlight),
                    description = stringResource(Res.string.settings_nav_bar_glass_highlight_description),
                    checked = overrides.withHighlight ?: true,
                    isTablet = isTablet,
                    onCheckedChange = { onChanged(overrides.copy(withHighlight = it)) },
                )
                LiquidGlassEffectSlider(
                    title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                    value = overrides.cornerRadiusPercent ?: recipe.cornerRadiusPercent,
                    valueRange = 0f..50f,
                    valueLabel = overrides.cornerRadiusPercent?.let { "${it.roundToInt()}%" }
                        ?: stringResource(Res.string.settings_liquid_glass_default),
                    isTablet = isTablet,
                    steps = 49,
                    onValueChange = { onChanged(overrides.copy(cornerRadiusPercent = it)) },
                )
                LiquidGlassEffectSlider(
                    title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                    value = overrides.blurRadiusDp ?: recipe.blurRadiusDp,
                    valueRange = 0f..32f,
                    valueLabel = overrides.blurRadiusDp?.let { "${it.roundToInt()} dp" }
                        ?: stringResource(Res.string.settings_liquid_glass_default),
                    isTablet = isTablet,
                    steps = 31,
                    onValueChange = { onChanged(overrides.copy(blurRadiusDp = it)) },
                )
                if (refractionSupported) {
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                        value = overrides.refractionHeightDp ?: recipe.refractionHeightDp,
                        valueRange = 0f..48f,
                        valueLabel = overrides.refractionHeightDp?.let { "${it.roundToInt()} dp" }
                            ?: stringResource(Res.string.settings_liquid_glass_default),
                        isTablet = isTablet,
                        steps = 47,
                        onValueChange = { onChanged(overrides.copy(refractionHeightDp = it)) },
                    )
                    LiquidGlassEffectSlider(
                        title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                        value = overrides.refractionAmountDp ?: recipe.refractionAmountDp,
                        valueRange = 0f..48f,
                        valueLabel = overrides.refractionAmountDp?.let { "${it.roundToInt()} dp" }
                            ?: stringResource(Res.string.settings_liquid_glass_default),
                        isTablet = isTablet,
                        steps = 47,
                        onValueChange = { onChanged(overrides.copy(refractionAmountDp = it)) },
                    )
                }
                LiquidGlassEffectSlider(
                    title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                    value = overrides.tintOpacity ?: recipe.tintOpacity,
                    valueRange = 0f..1f,
                    valueLabel = overrides.tintOpacity?.let { "${(it * 100f).roundToInt()}%" }
                        ?: stringResource(Res.string.settings_liquid_glass_default),
                    isTablet = isTablet,
                    steps = 19,
                    onValueChange = { onChanged(overrides.copy(tintOpacity = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_vibrancy),
                    description = stringResource(Res.string.settings_nav_bar_glass_vibrancy_description),
                    checked = overrides.withVibrancy ?: true,
                    isTablet = isTablet,
                    onCheckedChange = { onChanged(overrides.copy(withVibrancy = it)) },
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_shadow),
                    description = stringResource(Res.string.settings_nav_bar_glass_shadow_description),
                    checked = overrides.withShadow ?: false,
                    isTablet = isTablet,
                    onCheckedChange = { onChanged(overrides.copy(withShadow = it)) },
                )
                if (refractionSupported) {
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
                }
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
    NuvioBottomSheetDivider()
}

@Composable
private fun liquidGlassSurfaceTitle(surface: LiquidGlassSurface): String =
    stringResource(
        when (surface) {
            LiquidGlassSurface.PLAYER -> Res.string.settings_liquid_glass_surface_player
            LiquidGlassSurface.STREAM_SELECTION -> Res.string.settings_liquid_glass_surface_stream_selection
            LiquidGlassSurface.DETAILS -> Res.string.settings_liquid_glass_surface_details
            LiquidGlassSurface.SEARCH -> Res.string.settings_liquid_glass_surface_search
            LiquidGlassSurface.LIBRARY -> Res.string.settings_liquid_glass_surface_library
            LiquidGlassSurface.SETTINGS -> Res.string.settings_liquid_glass_surface_settings
            LiquidGlassSurface.BOTTOM_SHEETS -> Res.string.settings_liquid_glass_surface_bottom_sheets
        },
    )

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
            when (surface) {
                LiquidGlassSurface.PLAYER -> Row(
                    modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurfacePreviewIcon(surface, Icons.Rounded.Close, CircleShape, recipe)
                    SurfacePreviewIcon(surface, Icons.Rounded.PlayArrow, CircleShape, recipe)
                    SurfacePreviewIcon(surface, Icons.Rounded.Settings, RoundedCornerShape(14.dp), recipe)
                }

                LiquidGlassSurface.STREAM_SELECTION -> Row(
                    modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.Search, stringResource(Res.string.streams_search_placeholder),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                    SurfacePreviewIcon(surface, Icons.Rounded.Close, CircleShape, recipe)
                }

                LiquidGlassSurface.DETAILS -> Row(
                    modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.PlayArrow, stringResource(Res.string.action_play),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.Settings, stringResource(Res.string.details_actions_menu_label),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                LiquidGlassSurface.SEARCH -> Row(
                    modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.Search, stringResource(Res.string.compose_search_placeholder),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                    SurfacePreviewIcon(surface, Icons.Rounded.Close, CircleShape, recipe)
                }

                LiquidGlassSurface.LIBRARY -> Row(
                    modifier = Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.PlayArrow, stringResource(Res.string.library_source_saved),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    SurfacePreviewPill(
                        surface, recipe, Icons.Rounded.Search, stringResource(Res.string.library_source_cloud),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    SurfacePreviewIcon(surface, Icons.Rounded.Settings, CircleShape, recipe)
                }

                LiquidGlassSurface.SETTINGS -> SurfacePreviewCard(
                    surface = surface,
                    recipe = recipe,
                    title = stringResource(Res.string.settings_liquid_glass_surface_settings),
                    subtitle = stringResource(Res.string.settings_liquid_glass_surface_description),
                    icon = Icons.Rounded.Settings,
                )

                LiquidGlassSurface.BOTTOM_SHEETS -> SurfacePreviewCard(
                    surface = surface,
                    recipe = recipe,
                    title = stringResource(Res.string.settings_liquid_glass_surface_bottom_sheets),
                    subtitle = stringResource(Res.string.settings_liquid_glass_surface_description),
                    icon = Icons.Rounded.Search,
                )
            }
        }
    }
}

@Composable
private fun SurfacePreviewPill(
    surface: LiquidGlassSurface,
    recipe: LiquidGlassSurfaceRecipe,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .liquidGlassForSurface(
                surface = surface,
                shape = shape,
                tint = Color.White.copy(alpha = recipe.tintOpacity),
                fallback = Color.White.copy(alpha = 0.12f),
                blurRadius = recipe.blurRadiusDp.dp,
                refractionHeight = recipe.refractionHeightDp.dp,
                refractionAmount = recipe.refractionAmountDp.dp,
                withVibrancy = true,
                withHighlight = true,
                preview = true,
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = liquidGlassIconTint(surface, Color.White, preview = true),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun SurfacePreviewCard(
    surface: LiquidGlassSurface,
    recipe: LiquidGlassSurfaceRecipe,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .padding(horizontal = 12.dp)
            .liquidGlassForSurface(
                surface = surface,
                shape = shape,
                tint = Color.White.copy(alpha = recipe.tintOpacity),
                fallback = Color.White.copy(alpha = 0.12f),
                blurRadius = recipe.blurRadiusDp.dp,
                refractionHeight = recipe.refractionHeightDp.dp,
                refractionAmount = recipe.refractionAmountDp.dp,
                withVibrancy = true,
                withHighlight = true,
                preview = true,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfacePreviewIcon(surface, icon, RoundedCornerShape(12.dp), recipe)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(subtitle, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
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
    Box(
        modifier = Modifier
            .size(42.dp)
            .liquidGlassForSurface(
                surface = surface,
                shape = shape,
                tint = Color.White.copy(alpha = recipe.tintOpacity),
                fallback = Color.White.copy(alpha = 0.12f),
                blurRadius = recipe.blurRadiusDp.dp,
                refractionHeight = recipe.refractionHeightDp.dp,
                refractionAmount = recipe.refractionAmountDp.dp,
                withVibrancy = true,
                withHighlight = true,
                preview = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = liquidGlassIconTint(surface, Color.White, preview = true),
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
        LiquidGlassSurface.BOTTOM_SHEETS -> LiquidGlassSurfaceRecipe(28f, 24f, 16f, 32f, 0.35f)
    }

/** The preview shape follows the current live override; null continues to use this surface's recipe. */
