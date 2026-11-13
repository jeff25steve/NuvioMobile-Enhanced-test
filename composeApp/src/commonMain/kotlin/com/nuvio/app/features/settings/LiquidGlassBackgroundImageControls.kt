package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background_description
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background_choose
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background_clear
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background_saving
import nuvio.composeapp.generated.resources.settings_appearance_custom_settings_background_error
import nuvio.composeapp.generated.resources.settings_liquid_glass_library_background_title
import nuvio.composeapp.generated.resources.settings_liquid_glass_library_background_description
import nuvio.composeapp.generated.resources.settings_liquid_glass_library_background_choose
import nuvio.composeapp.generated.resources.settings_liquid_glass_library_background_remove
import nuvio.composeapp.generated.resources.settings_liquid_glass_library_background_error
import org.jetbrains.compose.resources.stringResource

internal enum class LiquidGlassBackgroundImageTarget {
    SETTINGS,
    LIBRARY_CLOUD,
}

internal fun liquidGlassBackgroundImageTarget(
    surface: LiquidGlassSurface,
): LiquidGlassBackgroundImageTarget? = when (surface) {
    LiquidGlassSurface.SETTINGS -> LiquidGlassBackgroundImageTarget.SETTINGS
    LiquidGlassSurface.LIBRARY_CLOUD_FILTERS,
    LiquidGlassSurface.LIBRARY_CLOUD_SEARCH,
    LiquidGlassSurface.LIBRARY_CLOUD_CARDS -> LiquidGlassBackgroundImageTarget.LIBRARY_CLOUD
    else -> null
}

/**
 * Offers profile-local wallpaper controls only on the two surfaces that render custom backdrops.
 * The Settings and Cloud images use independent storage keys and never overwrite one another.
 */
@Composable
internal fun LiquidGlassBackgroundImageControls(surface: LiquidGlassSurface) {
    val target = liquidGlassBackgroundImageTarget(surface) ?: return
    val isCloud = target == LiquidGlassBackgroundImageTarget.LIBRARY_CLOUD
    val imageUriFlow = remember(target) {
        ThemeSettingsRepository.ensureLoaded()
        if (isCloud) {
            ThemeSettingsRepository.customLibraryCloudBackgroundImageUri
        } else {
            ThemeSettingsRepository.customSettingsBackgroundImageUri
        }
    }
    val imageUri by imageUriFlow.collectAsStateWithLifecycle()
    var isSaving by remember(target) { mutableStateOf(false) }
    var hasError by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val picker = rememberSettingsBackgroundImagePicker(
        onImageSelected = { bytes ->
            val profileId = ProfileRepository.activeProfileId
            hasError = false
            isSaving = true
            scope.launch {
                val saved = try {
                    if (isCloud) {
                        ThemeSettingsRepository.saveCustomLibraryCloudBackgroundImageForStudio(bytes, profileId)
                    } else {
                        ThemeSettingsRepository.saveCustomSettingsBackgroundImageForStudio(bytes, profileId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                isSaving = false
                if (ProfileRepository.activeProfileId == profileId) {
                    hasError = !saved
                }
            }
        },
        onError = { hasError = true },
    )

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (isCloud) {
                stringResource(Res.string.settings_liquid_glass_library_background_title)
            } else {
                stringResource(Res.string.settings_appearance_custom_settings_background)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (isCloud) {
                stringResource(Res.string.settings_liquid_glass_library_background_description)
            } else {
                stringResource(Res.string.settings_appearance_custom_settings_background_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = !isSaving, onClick = { picker.launch() }) {
                Text(
                    if (isSaving) {
                        stringResource(Res.string.settings_appearance_custom_settings_background_saving)
                    } else if (isCloud) {
                        stringResource(Res.string.settings_liquid_glass_library_background_choose)
                    } else {
                        stringResource(Res.string.settings_appearance_custom_settings_background_choose)
                    },
                )
            }
            if (imageUri != null) {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        val profileId = ProfileRepository.activeProfileId
                        hasError = false
                        isSaving = true
                        scope.launch {
                            val cleared = try {
                                if (isCloud) {
                                    ThemeSettingsRepository.clearCustomLibraryCloudBackgroundImageForStudio(profileId)
                                } else {
                                    ThemeSettingsRepository.clearCustomSettingsBackgroundImageForStudio(profileId)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            isSaving = false
                            if (ProfileRepository.activeProfileId == profileId) {
                                hasError = !cleared
                            }
                        }
                    },
                ) {
                    Text(
                        if (isCloud) {
                            stringResource(Res.string.settings_liquid_glass_library_background_remove)
                        } else {
                            stringResource(Res.string.settings_appearance_custom_settings_background_clear)
                        },
                    )
                }
            }
        }
        if (hasError) {
            Text(
                text = if (isCloud) {
                    stringResource(Res.string.settings_liquid_glass_library_background_error)
                } else {
                    stringResource(Res.string.settings_appearance_custom_settings_background_error)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
