package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.LegacyPlayerButtonLayouts
import com.nuvio.app.features.player.PlayerSkipSecondsOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackInterfaceSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
    highlightedSearchAnchorKey: String? = null,
) {
    var showHoldToSpeedValueDialog by remember { mutableStateOf(false) }
    var skipDirectionDialog by remember { mutableStateOf<Boolean?>(null) } // true = forward
    var showToolbarLayoutDialog by remember { mutableStateOf(false) }
    var showLegacyBottomButtonsDialog by remember { mutableStateOf(false) }
    var showLegacyTopButtonsDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_interface),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_legacy_layout),
                    description = stringResource(Res.string.settings_playback_legacy_layout_description),
                    checked = settings.useLegacyPlayerLayout,
                    searchAnchorKey = "playback-interface-legacy-layout",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setUseLegacyPlayerLayout,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_title_logo),
                    description = stringResource(Res.string.settings_playback_show_title_logo_description),
                    checked = settings.showPlayerTitleLogo,
                    searchAnchorKey = "playback-interface-title-logo",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowPlayerTitleLogo,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_control_glass),
                    description = stringResource(Res.string.settings_playback_control_glass_description),
                    checked = settings.playerControlGlassEnabled,
                    searchAnchorKey = "playback-interface-control-glass",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPlayerControlGlassEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_skip_buttons),
                    description = stringResource(Res.string.settings_playback_show_skip_buttons_description),
                    checked = settings.showSkipButtons,
                    searchAnchorKey = "playback-interface-skip-buttons",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowSkipButtons,
                )
                if (!settings.useLegacyPlayerLayout) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_toolbar_layout),
                        searchAnchorKey = "playback-interface-toolbar-layout",
                        highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                        description = stringResource(Res.string.settings_playback_toolbar_layout_description),
                        isTablet = isTablet,
                        onClick = { showToolbarLayoutDialog = true },
                    )
                } else {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_legacy_top_buttons),
                        searchAnchorKey = "playback-interface-legacy-top-buttons",
                        highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                        description = stringResource(Res.string.settings_playback_legacy_top_buttons_description),
                        isTablet = isTablet,
                        onClick = { showLegacyTopButtonsDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_legacy_bottom_buttons),
                        searchAnchorKey = "playback-interface-legacy-bottom-buttons",
                        highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                        description = stringResource(Res.string.settings_playback_legacy_bottom_buttons_description),
                        isTablet = isTablet,
                        onClick = { showLegacyBottomButtonsDialog = true },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_loading_overlay),
                    description = stringResource(Res.string.settings_playback_show_loading_overlay_description),
                    checked = settings.showLoadingOverlay,
                    searchAnchorKey = "playback-interface-loading-overlay",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_exo_chapters),
                    description = stringResource(Res.string.settings_playback_exo_chapters_description),
                    checked = settings.androidExoChaptersEnabled,
                    searchAnchorKey = "playback-interface-exo-chapters",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAndroidExoChaptersEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.playback_show_loading_status),
                    description = stringResource(Res.string.playback_show_loading_status_sub),
                    checked = settings.showPlayerLoadingStatus,
                    searchAnchorKey = "playback-interface-loading-status",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowPlayerLoadingStatus,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_pause_overlay),
                    description = stringResource(Res.string.settings_playback_pause_overlay_description),
                    checked = settings.pauseOverlayEnabled,
                    searchAnchorKey = "playback-interface-pause-overlay",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPauseOverlayEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_parental_guide),
                    description = stringResource(Res.string.settings_playback_parental_guide_description),
                    checked = settings.showParentalGuide,
                    searchAnchorKey = "playback-interface-parental-guide",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowParentalGuide,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_movie_recommendations),
                    description = stringResource(Res.string.settings_playback_movie_recommendations_description),
                    checked = settings.movieRecommendationsEnabled,
                    searchAnchorKey = "playback-interface-movie-recommendations",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setMovieRecommendationsEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_mini_player),
                    description = stringResource(Res.string.settings_playback_mini_player_description),
                    checked = settings.miniPlayerEnabled,
                    searchAnchorKey = "playback-interface-mini-player",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setMiniPlayerEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_seek_preview),
                    description = stringResource(Res.string.settings_playback_seek_preview_description),
                    checked = settings.seekPreviewEnabled,
                    searchAnchorKey = "playback-interface-seek-preview",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setSeekPreviewEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_touch_gestures),
                    description = stringResource(Res.string.settings_playback_touch_gestures_description),
                    checked = settings.touchGesturesEnabled,
                    searchAnchorKey = "playback-interface-touch-gestures",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setTouchGesturesEnabled,
                )
                if (settings.touchGesturesEnabled && !settings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_swipe_to_seek),
                        description = stringResource(Res.string.settings_playback_swipe_to_seek_description),
                        checked = settings.swipeToSeekEnabled,
                        searchAnchorKey = "playback-interface-swipe-to-seek",
                        highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setSwipeToSeekEnabled,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_hold_to_speed),
                    description = stringResource(Res.string.settings_playback_hold_to_speed_description),
                    checked = settings.holdToSpeedEnabled,
                    searchAnchorKey = "playback-interface-hold-to-speed",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setHoldToSpeedEnabled,
                )
                if (settings.holdToSpeedEnabled && !settings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_hold_speed),
                        searchAnchorKey = "playback-interface-hold-speed-value",
                        highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                        description = formatPlaybackSpeedLabel(settings.holdToSpeedValue),
                        isTablet = isTablet,
                        onClick = { showHoldToSpeedValueDialog = true },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_skip_backward),
                    searchAnchorKey = "playback-interface-skip-backward",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    description = stringResource(
                        Res.string.settings_playback_skip_seconds_value,
                        settings.skipBackwardSeconds,
                    ),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { skipDirectionDialog = false },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_skip_forward),
                    searchAnchorKey = "playback-interface-skip-forward",
                    highlightedSearchAnchorKey = highlightedSearchAnchorKey,
                    description = stringResource(
                        Res.string.settings_playback_skip_seconds_value,
                        settings.skipForwardSeconds,
                    ),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { skipDirectionDialog = true },
                )
            }
        }
    }

    if (showToolbarLayoutDialog) {
        PlayerToolbarSettingsDialog(
            layout = settings.toolbarLayout,
            onLayoutChange = PlayerSettingsRepository::setToolbarLayout,
            onDismiss = { showToolbarLayoutDialog = false },
        )
    }

    if (showLegacyTopButtonsDialog) {
        LegacyPlayerButtonsSettingsDialog(
            title = stringResource(Res.string.settings_playback_legacy_top_buttons),
            layout = settings.legacyTopButtons,
            defaultLayout = LegacyPlayerButtonLayouts.DefaultTop,
            label = { it.labelRes },
            onLayoutChange = PlayerSettingsRepository::setLegacyTopButtons,
            onDismiss = { showLegacyTopButtonsDialog = false },
        )
    }

    if (showLegacyBottomButtonsDialog) {
        LegacyPlayerButtonsSettingsDialog(
            title = stringResource(Res.string.settings_playback_legacy_bottom_buttons),
            layout = settings.legacyBottomButtons,
            defaultLayout = LegacyPlayerButtonLayouts.DefaultBottom,
            label = { it.labelRes },
            onLayoutChange = PlayerSettingsRepository::setLegacyBottomButtons,
            onDismiss = { showLegacyBottomButtonsDialog = false },
        )
    }

    skipDirectionDialog?.let { forward ->
        SkipSecondsDialog(
            title = stringResource(
                if (forward) Res.string.settings_playback_skip_forward else Res.string.settings_playback_skip_backward,
            ),
            selectedSeconds = if (forward) settings.skipForwardSeconds else settings.skipBackwardSeconds,
            onSelected = { seconds ->
                if (forward) {
                    PlayerSettingsRepository.setSkipForwardSeconds(seconds)
                } else {
                    PlayerSettingsRepository.setSkipBackwardSeconds(seconds)
                }
                skipDirectionDialog = null
            },
            onDismiss = { skipDirectionDialog = null },
        )
    }

    if (showHoldToSpeedValueDialog) {
        HoldToSpeedValueDialog(
            selectedSpeed = settings.holdToSpeedValue,
            onSpeedSelected = { speed ->
                PlayerSettingsRepository.setHoldToSpeedValue(speed)
                showHoldToSpeedValueDialog = false
            },
            onDismiss = { showHoldToSpeedValueDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HoldToSpeedValueDialog(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hold_speed),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { speed ->
                val isSelected = speed == selectedSpeed
                DialogOption(
                    text = formatPlaybackSpeedLabel(speed),
                    selected = isSelected,
                    onClick = { onSpeedSelected(speed) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun SkipSecondsDialog(
    title: String,
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = stringResource(Res.string.settings_playback_skip_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerSkipSecondsOptions.forEach { seconds ->
                DialogOption(
                    text = stringResource(Res.string.settings_playback_skip_seconds_value, seconds),
                    selected = seconds == selectedSeconds,
                    onClick = { onSelected(seconds) },
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
        }
    }
}
