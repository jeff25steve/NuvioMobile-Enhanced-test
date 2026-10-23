package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
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
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.library.CloudLibrarySearchField
import com.nuvio.app.features.player.PlayerToolbar
import com.nuvio.app.features.streams.StreamSearchField
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassSurfaceOverrides
import com.nuvio.app.core.ui.glass.LiquidGlassSurfacesSettings
import com.nuvio.app.core.ui.glass.LocalLiquidGlassBackdrop
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
import com.nuvio.app.core.ui.glass.liquidGlassForSurface
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
import nuvio.composeapp.generated.resources.details_actions_menu_label
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
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    val recipe = remember(surface) { surfacePreviewRecipe(surface) }
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
    val enabled = overrides.enabled ?: surface.defaultEnabled
    val refractionSupported = isLiquidRefractionSupported()
    val recipe = remember(surface) { surfacePreviewRecipe(surface) }

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
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    // Prefer the real app content captured behind this sheet. Do not invent a gradient or fake
    // controls: if a platform cannot provide a backdrop, the production glass fallback is shown.
    val previewBackdrop = LocalNuvioSheetBackdrop.current?.backdrop?.takeIf { it.isReady }
        ?: LocalLiquidGlassBackdrop.current

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides previewBackdrop) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (surface) {
                LiquidGlassSurface.PLAYER -> PlayerToolbar(
                    isLocked = false,
                    onLockToggle = {},
                    onBack = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )

                LiquidGlassSurface.STREAM_SELECTION -> StreamSearchField(
                    query = "",
                    onQueryChange = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    preview = true,
                )

                LiquidGlassSurface.DETAILS -> DetailActionButtons(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    isTablet = isTablet,
                    secondaryActions = listOf(
                        DetailSecondaryAction(
                            label = stringResource(Res.string.details_actions_menu_label),
                            icon = Icons.Rounded.Settings,
                        ),
                    ),
                )

                LiquidGlassSurface.SEARCH -> {
                    val shape = RoundedCornerShape(percent = recipe.cornerRadiusPercent.roundToInt())
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
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
            }
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
