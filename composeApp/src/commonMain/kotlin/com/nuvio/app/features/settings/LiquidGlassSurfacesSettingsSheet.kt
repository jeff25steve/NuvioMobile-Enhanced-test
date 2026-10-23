package com.nuvio.app.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.LocalNuvioSheetBackdrop
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSheetSurface
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.usesNativeNuvioBottomSheet
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.library.CloudLibrarySearchField
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerControlsShell
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.streams.StreamSearchField
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.core.ui.glass.LiquidGlassSurfacesSettings
import com.nuvio.app.core.ui.glass.LocalLiquidGlassBackdrop
import com.nuvio.app.core.ui.glass.LocalLiquidGlassPreviewOverrides
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
import com.nuvio.app.core.ui.glass.liquidGlassForSurface
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
import nuvio.composeapp.generated.resources.compose_search_placeholder
import nuvio.composeapp.generated.resources.compose_settings_page_appearance
import nuvio.composeapp.generated.resources.compose_settings_root_appearance_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_surface_bottom_sheets
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun LiquidGlassSurfaceSettingsDrafts(
    content: @Composable (LiquidGlassSurfacesSettings) -> Unit,
) {
    val settings by ThemeSettingsRepository.liquidGlassSurfaceSettings.collectAsStateWithLifecycle()
    content(settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    val listState = rememberLazyListState()
    LaunchedEffect(selectedSurfaceName) {
        listState.scrollToItem(0)
    }

    val previewCaptureOwner = LocalNuvioSheetBackdrop.current
    DisposableEffect(previewCaptureOwner) {
        previewCaptureOwner?.let { it.activeGlassPreviews += 1 }
        onDispose {
            previewCaptureOwner?.let {
                it.activeGlassPreviews = (it.activeGlassPreviews - 1).coerceAtLeast(0)
            }
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
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
                stickyHeader(key = "liquid-glass-preview-${selectedSurface.name}") {
                    LiquidGlassSurfaceEditorHeader(
                        surface = selectedSurface,
                        overrides = settings.forSurface(selectedSurface),
                        isTablet = isTablet,
                        onBack = { selectedSurfaceName = null },
                    )
                }
                item(key = "liquid-glass-controls-${selectedSurface.name}") {
                    LiquidGlassSurfaceEditorControls(
                        surface = selectedSurface,
                        overrides = settings.forSurface(selectedSurface),
                        isTablet = isTablet,
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
private fun LiquidGlassSurfaceEditorHeader(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    val recipe = remember(surface, overrides) { surfacePreviewRecipe(surface, overrides) }
    val title = liquidGlassSurfaceTitle(surface)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nuvioSheetSurface(
                backdrop = LocalNuvioSheetBackdrop.current?.backdrop,
                shape = RectangleShape,
                containerColor = MaterialTheme.nuvio.colors.surfaceSheet,
                withHighlight = false,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            overrides = overrides,
            isTablet = isTablet,
            modifier = Modifier.padding(horizontal = if (isTablet) 24.dp else 16.dp),
        )

    }
}

@Composable
private fun LiquidGlassSurfaceEditorControls(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    onChanged: (LiquidGlassSurfaceOverrides) -> Unit,
) {
    val title = liquidGlassSurfaceTitle(surface)
    val enabled = overrides.enabled ?: surface.defaultEnabled
    val refractionSupported = isLiquidRefractionSupported()
    val recipe = remember(surface, overrides) { surfacePreviewRecipe(surface, overrides) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    overrides: LiquidGlassSurfaceOverrides,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .then(
                if (surface == LiquidGlassSurface.PLAYER) {
                    Modifier.widthIn(max = LIQUID_GLASS_PLAYER_PREVIEW_DESIGN_WIDTH_DP.dp)
                } else {
                    Modifier
                },
            )
            .fillMaxWidth(),
    ) {
        val previewWidth = maxWidth
        val previewHeight = when (surface) {
            LiquidGlassSurface.PLAYER -> liquidGlassPlayerPreviewHeight(previewWidth)
            LiquidGlassSurface.SEARCH -> 176.dp
            LiquidGlassSurface.DETAILS -> 120.dp
            else -> 88.dp
        }

        Box(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight)
                .clip(RoundedCornerShape(14.dp))
                .align(Alignment.Center),
        ) {
        when (surface) {
            LiquidGlassSurface.PLAYER -> LiquidGlassPlayerPreview(
                overrides = overrides,
                modifier = Modifier.fillMaxSize(),
            )

            LiquidGlassSurface.SEARCH -> LiquidGlassSearchPreview(
                recipe = recipe,
                overrides = overrides,
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                val previewBackdrop = LocalNuvioSheetBackdrop.current?.backdrop?.takeIf { it.isReady }
                    ?: LocalLiquidGlassBackdrop.current
                CompositionLocalProvider(LocalLiquidGlassBackdrop provides previewBackdrop) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (surface) {
                            LiquidGlassSurface.STREAM_SELECTION -> StreamSearchField(
                                query = "",
                                onQueryChange = {},
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                preview = true,
                            )

                            LiquidGlassSurface.DETAILS -> DetailActionButtons(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                isTablet = isTablet,
                                onDownloadClick = {},
                            )

                            LiquidGlassSurface.LIBRARY -> {
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .liquidGlassForSurface(
                                            surface = LiquidGlassSurface.LIBRARY,
                                            shape = shape,
                                            tint = MaterialTheme.colorScheme.surface.copy(alpha = recipe.tintOpacity),
                                            fallback = MaterialTheme.nuvio.colors.surfaceCard,
                                            blurRadius = recipe.blurRadiusDp.dp,
                                            refractionHeight = recipe.refractionHeightDp.dp,
                                            refractionAmount = recipe.refractionAmountDp.dp,
                                            preview = true,
                                        ),
                                ) {
                                    CloudLibrarySearchField(
                                        query = "",
                                        onQueryChange = {},
                                        modifier = Modifier.fillMaxWidth(),
                                        transparentContainer = true,
                                    )
                                }
                            }

                            LiquidGlassSurface.SETTINGS -> SettingsGroup(
                                isTablet = isTablet,
                                previewGlass = true,
                            ) {
                                SettingsNavigationRow(
                                    title = stringResource(Res.string.compose_settings_page_appearance),
                                    description = stringResource(Res.string.compose_settings_root_appearance_description),
                                    icon = Icons.Rounded.Settings,
                                    isTablet = isTablet,
                                    onClick = {},
                                )
                            }

                            LiquidGlassSurface.BOTTOM_SHEETS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .nuvioSheetSurface(
                                            backdrop = previewBackdrop,
                                            shape = RoundedCornerShape(28.dp),
                                            containerColor = MaterialTheme.nuvio.colors.surfaceSheet,
                                            withHighlight = false,
                                            preview = true,
                                        ),
                                ) {
                                    NuvioBottomSheetActionRow(
                                        title = stringResource(Res.string.compose_settings_page_appearance),
                                        icon = Icons.Rounded.Settings,
                                        onClick = {},
                                    )
                                }
                            }

                            LiquidGlassSurface.PLAYER,
                            LiquidGlassSurface.SEARCH,
                            -> Unit
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun LiquidGlassPlayerPreview(
    overrides: LiquidGlassSurfaceOverrides,
    modifier: Modifier = Modifier,
) {
    val previewBackdrop = rememberNuvioBackdrop()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val previewScale = liquidGlassPlayerPreviewScale(maxWidth)
        val metrics = remember {
            PlayerLayoutMetrics.fromWidth(LIQUID_GLASS_PLAYER_PREVIEW_DESIGN_WIDTH_DP.dp)
        }
        val durationMs = 10_871_000L
        val playbackSnapshot = remember {
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                durationMs = durationMs,
                positionMs = 0L,
                bufferedPositionMs = 3_760_000L,
                playbackSpeed = 1f,
                videoWidth = 3840,
                videoHeight = 2160,
                chapters = listOf(
                    PlayerChapter("Opening", 0L),
                    PlayerChapter("Arrival", 1_500_000L),
                    PlayerChapter("Midpoint", 3_400_000L),
                    PlayerChapter("Finale", 7_100_000L),
                ),
            )
        }

        // One landscape design canvas contains both the artwork source and the real Nuvio player
        // controls. They are measured at the same 800x450 size and uniformly scaled as a unit.
        Box(
            modifier = Modifier
                .requiredSize(
                    width = LIQUID_GLASS_PLAYER_PREVIEW_DESIGN_WIDTH_DP.dp,
                    height = LIQUID_GLASS_PLAYER_PREVIEW_DESIGN_WIDTH_DP.dp * 9f / 16f,
                )
                .graphicsLayer {
                    scaleX = previewScale
                    scaleY = previewScale
                    transformOrigin = TransformOrigin.Center
                }
                .align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nuvioBackdropSource(previewBackdrop),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0C1827),
                                    Color(0xFF273A52),
                                    Color(0xFF493447),
                                    Color(0xFF101018),
                                ),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(390.dp)
                        .align(Alignment.CenterStart)
                        .offset(x = 105.dp, y = (-24).dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xAA3F9CB7), Color(0x663E607A), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(330.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 82.dp, y = 68.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0x997C4C75), Color(0x33583E64), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )
            }

            CompositionLocalProvider(
                LocalLiquidGlassBackdrop provides previewBackdrop,
                LocalLiquidGlassPreviewOverrides provides overrides,
            ) {
                PlayerControlsShell(
                    title = "Avengers: Endgame",
                    streamTitle = "One Last Surprise",
                    providerName = "Torrentio",
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    playbackSnapshot = playbackSnapshot,
                    displayedPositionMs = 0L,
                    metrics = metrics,
                    resizeMode = PlayerResizeMode.Fit,
                    isLocked = false,
                    releaseInfo = "Via 2160p | Cached",
                    badges = emptyList(),
                    onLockToggle = {},
                    onBack = {},
                    onPartyClick = {},
                    partyActive = false,
                    onCastClick = {},
                    isCasting = false,
                    onPictureInPictureClick = {},
                    onOrientationToggleClick = {},
                    isPortraitPlayback = false,
                    onTogglePlayback = {},
                    onSeekBack = {},
                    onSeekForward = {},
                    skipBackwardSeconds = 10,
                    skipForwardSeconds = 10,
                    onResizeModeClick = {},
                    onSpeedClick = {},
                    onSubtitleClick = {},
                    onAudioClick = {},
                    onVideoSettingsClick = {},
                    onInfoClick = {},
                    onRateClick = {},
                    onSourcesClick = {},
                    onEpisodesClick = {},
                    onChaptersClick = {},
                    onLiveChannelsClick = {},
                    qualityLabel = "2160p",
                    onQualityClick = {},
                    onOpenInExternalPlayer = {},
                    onSubmitIntroClick = {},
                    onSwitchEngineClick = {},
                    onScrubChange = {},
                    onScrubFinished = {},
                    horizontalSafePadding = metrics.horizontalPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassSearchPreview(
    recipe: LiquidGlassSurfaceRecipe,
    overrides: LiquidGlassSurfaceOverrides,
    modifier: Modifier = Modifier,
) {
    val previewBackdrop = rememberNuvioBackdrop()
    val listState = rememberLazyListState()
    val shape = overrides.cornerRadiusPercent?.let { RoundedCornerShape(percent = it.roundToInt()) }
        ?: RoundedCornerShape(18.dp)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nuvioBackdropSource(previewBackdrop),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(top = 42.dp, bottom = 12.dp, start = 10.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = liquidGlassSearchPreviewPosters.chunked(3),
                    key = { row -> row.first().title },
                ) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { poster ->
                            LiquidGlassSearchPreviewPosterTile(
                                poster = poster,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Soft scroll-edge fade keeps the floating search field legible while poster art moves beneath it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.30f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        CompositionLocalProvider(LocalLiquidGlassBackdrop provides previewBackdrop) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .liquidGlassForSurface(
                        surface = LiquidGlassSurface.SEARCH,
                        shape = shape,
                        tint = MaterialTheme.colorScheme.surface.copy(alpha = recipe.tintOpacity),
                        fallback = MaterialTheme.nuvio.colors.surfaceCard,
                        blurRadius = recipe.blurRadiusDp.dp,
                        refractionHeight = recipe.refractionHeightDp.dp,
                        refractionAmount = recipe.refractionAmountDp.dp,
                        preview = true,
                    )
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 8.dp),
            ) {
                NuvioInputField(
                    value = "",
                    onValueChange = {},
                    placeholder = stringResource(Res.string.compose_search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                )
            }
        }
    }
}

private data class LiquidGlassSearchPreviewPoster(
    val title: String,
    val subtitle: String,
    val colors: List<Color>,
    val glow: Color,
)

private val liquidGlassSearchPreviewPosters = listOf(
    LiquidGlassSearchPreviewPoster("AVENGERS", "ENDGAME", listOf(Color(0xFF173550), Color(0xFF322D3B)), Color(0xFF6BAFC8)),
    LiquidGlassSearchPreviewPoster("DUNE", "PART TWO", listOf(Color(0xFF6F4B31), Color(0xFF1E2838)), Color(0xFFE4B36B)),
    LiquidGlassSearchPreviewPoster("THE BATMAN", "GOTHAM", listOf(Color(0xFF421D28), Color(0xFF10151D)), Color(0xFFB84F58)),
    LiquidGlassSearchPreviewPoster("ARCANE", "LEAGUE OF LEGENDS", listOf(Color(0xFF31546B), Color(0xFF221B3D)), Color(0xFF69D8DA)),
    LiquidGlassSearchPreviewPoster("INTERSTELLAR", "BEYOND STARS", listOf(Color(0xFF263B55), Color(0xFF0F121B)), Color(0xFF94C5E7)),
    LiquidGlassSearchPreviewPoster("SPIDER-VERSE", "ACROSS THE MULTIVERSE", listOf(Color(0xFF592A5A), Color(0xFF172A4A)), Color(0xFFCC5CC7)),
    LiquidGlassSearchPreviewPoster("THE MATRIX", "RELOADED", listOf(Color(0xFF183D31), Color(0xFF090D0C)), Color(0xFF5EC69D)),
    LiquidGlassSearchPreviewPoster("LOKI", "SEASON TWO", listOf(Color(0xFF4B5B37), Color(0xFF20201C)), Color(0xFFB4D472)),
    LiquidGlassSearchPreviewPoster("ARRIVAL", "THE UNKNOWN", listOf(Color(0xFF4C6174), Color(0xFF171A26)), Color(0xFF9DCAF1)),
)

@Composable
private fun LiquidGlassSearchPreviewPosterTile(
    poster: LiquidGlassSearchPreviewPoster,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(poster.colors)),
        )
        Box(
            modifier = Modifier
                .size(92.dp)
                .align(Alignment.TopEnd)
                .offset(x = 26.dp, y = (-15).dp)
                .background(
                    Brush.radialGradient(listOf(poster.glow.copy(alpha = 0.82f), Color.Transparent)),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))),
                ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = poster.title,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = poster.subtitle,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class LiquidGlassSurfaceRecipe(
    val cornerRadiusPercent: Float,
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val tintOpacity: Float,
)

private fun surfacePreviewRecipe(
    surface: LiquidGlassSurface,
    overrides: LiquidGlassSurfaceOverrides,
): LiquidGlassSurfaceRecipe {
    val defaults = when (surface) {
        LiquidGlassSurface.PLAYER -> LiquidGlassSurfaceRecipe(50f, 6f, 8f, 12f, 0.08f)
        LiquidGlassSurface.STREAM_SELECTION -> LiquidGlassSurfaceRecipe(50f, 14f, 8f, 12f, 0.28f)
        LiquidGlassSurface.DETAILS -> LiquidGlassSurfaceRecipe(40f, 16f, 10f, 16f, 0.30f)
        LiquidGlassSurface.SEARCH -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
        LiquidGlassSurface.LIBRARY -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
        LiquidGlassSurface.SETTINGS -> LiquidGlassSurfaceRecipe(36f, 14f, 8f, 12f, 0.18f)
        LiquidGlassSurface.BOTTOM_SHEETS -> LiquidGlassSurfaceRecipe(28f, 24f, 16f, 32f, 0.35f)
    }
    return defaults.copy(
        cornerRadiusPercent = overrides.cornerRadiusPercent ?: defaults.cornerRadiusPercent,
        blurRadiusDp = overrides.blurRadiusDp ?: defaults.blurRadiusDp,
        refractionHeightDp = overrides.refractionHeightDp ?: defaults.refractionHeightDp,
        refractionAmountDp = overrides.refractionAmountDp ?: defaults.refractionAmountDp,
        tintOpacity = overrides.tintOpacity ?: defaults.tintOpacity,
    )
}

/** The preview shape follows the current live override; null continues to use this surface's recipe. */
