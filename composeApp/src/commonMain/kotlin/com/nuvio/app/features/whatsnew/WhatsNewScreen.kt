package com.nuvio.app.features.whatsnew

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.features.updater.UpdatePreferences
import com.nuvio.app.features.updater.VersionUtils
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_collapse
import nuvio.composeapp.generated.resources.action_expand
import nuvio.composeapp.generated.resources.compose_settings_page_whats_new
import nuvio.composeapp.generated.resources.updates_channel_beta
import nuvio.composeapp.generated.resources.updates_channel_stable
import nuvio.composeapp.generated.resources.whats_new_cached
import nuvio.composeapp.generated.resources.whats_new_category_features
import nuvio.composeapp.generated.resources.whats_new_category_fixes
import nuvio.composeapp.generated.resources.whats_new_category_localization
import nuvio.composeapp.generated.resources.whats_new_category_other
import nuvio.composeapp.generated.resources.whats_new_category_performance
import nuvio.composeapp.generated.resources.whats_new_current_version
import nuvio.composeapp.generated.resources.whats_new_hide_changes
import nuvio.composeapp.generated.resources.whats_new_last_checked_days
import nuvio.composeapp.generated.resources.whats_new_last_checked_hours
import nuvio.composeapp.generated.resources.whats_new_last_checked_just_now
import nuvio.composeapp.generated.resources.whats_new_last_checked_minutes
import nuvio.composeapp.generated.resources.whats_new_latest_release
import nuvio.composeapp.generated.resources.whats_new_load_failed
import nuvio.composeapp.generated.resources.whats_new_no_release_notes
import nuvio.composeapp.generated.resources.whats_new_no_releases
import nuvio.composeapp.generated.resources.whats_new_open_github
import nuvio.composeapp.generated.resources.whats_new_refresh
import nuvio.composeapp.generated.resources.whats_new_refreshing
import nuvio.composeapp.generated.resources.whats_new_release_format
import nuvio.composeapp.generated.resources.whats_new_since_your_version
import nuvio.composeapp.generated.resources.whats_new_contributor_credit
import nuvio.composeapp.generated.resources.whats_new_show_all_changes
import nuvio.composeapp.generated.resources.whats_new_status_current_ahead
import nuvio.composeapp.generated.resources.whats_new_status_update_available
import nuvio.composeapp.generated.resources.whats_new_status_unknown
import nuvio.composeapp.generated.resources.whats_new_status_updates_available
import nuvio.composeapp.generated.resources.whats_new_status_up_to_date
import nuvio.composeapp.generated.resources.whats_new_unavailable
import nuvio.composeapp.generated.resources.whats_new_recent_releases
import nuvio.composeapp.generated.resources.whats_new_channel_format
import org.jetbrains.compose.resources.stringResource

private const val MAX_PREVIEW_NOTES = 5

@Composable
fun WhatsNewSettingsScreen(onBack: () -> Unit) {
    val channel by UpdatePreferences.shared.channel.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var refreshKey by rememberSaveable { mutableIntStateOf(0) }
    var lastHandledRefreshKey by rememberSaveable { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var loadedChannel by remember { mutableStateOf<UpdateChannel?>(null) }

    LaunchedEffect(channel, refreshKey) {
        val forceRefresh = refreshKey != 0 && refreshKey != lastHandledRefreshKey
        lastHandledRefreshKey = refreshKey
        val preserveContent = state is LoadState.Content && loadedChannel == channel
        if (!preserveContent) state = LoadState.Loading
        isRefreshing = true
        val result = WhatsNewRepository.load(
            channel = channel,
            currentVersion = AppVersionConfig.VERSION_NAME,
            forceRefresh = forceRefresh,
        )
        isRefreshing = false
        state = result.fold(
            onSuccess = LoadState::Content,
            onFailure = { LoadState.Error },
        )
        loadedChannel = channel
    }

    val channelLabel = stringResource(
        when (channel) {
            UpdateChannel.STABLE -> Res.string.updates_channel_stable
            UpdateChannel.BETA -> Res.string.updates_channel_beta
        },
    )

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_whats_new),
                onBack = onBack,
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(NuvioTokens.Icon.md),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(Res.string.whats_new_refresh),
                            )
                        }
                    }
                },
            )
        }

        when (val value = state) {
            LoadState.Loading -> item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NuvioLoadingIndicator()
                }
            }

            LoadState.Error -> item {
                NuvioSurfaceCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        Text(
                            text = stringResource(Res.string.whats_new_unavailable),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.nuvio.colors.textPrimary,
                        )
                        Text(
                            text = stringResource(Res.string.whats_new_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                        NuvioPrimaryButton(
                            text = stringResource(Res.string.whats_new_refresh),
                            onClick = { refreshKey++ },
                        )
                    }
                }
            }

            is LoadState.Content -> {
                val snapshot = value.content.snapshot

                item {
                    CurrentVersionCard(
                        version = snapshot.currentVersion,
                        channelLabel = channelLabel,
                        lastCheckedLabel = lastCheckedLabel(value.content.fetchedAtMillis),
                        status = versionStatus(snapshot, channelLabel),
                    )
                }

                if (isRefreshing) {
                    item {
                        Text(
                            text = stringResource(Res.string.whats_new_refreshing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s4),
                        )
                    }
                }

                if (snapshot.hasCompleteSinceVersion && snapshot.sinceYourVersion.isNotEmpty()) {
                    item {
                        NuvioSectionLabel(
                            text = stringResource(Res.string.whats_new_since_your_version),
                        )
                    }
                    items(
                        items = snapshot.sinceYourVersion,
                        key = { release -> "since:" + release.version },
                    ) { release ->
                        ReleaseHistoryRow(release)
                    }
                }

                snapshot.releases.firstOrNull()?.let { latest ->
                    item(key = "latest:" + latest.version) {
                        ReleaseCard(latest)
                    }
                }

                if (snapshot.releases.size > 1) {
                    item {
                        NuvioSectionLabel(
                            text = stringResource(Res.string.whats_new_recent_releases),
                        )
                    }
                    items(
                        items = snapshot.releases.drop(1),
                        key = { release -> release.version },
                    ) { release ->
                        ReleaseHistoryRow(release)
                    }
                }

                if (snapshot.releases.isEmpty()) {
                    item { Text(text = stringResource(Res.string.whats_new_no_releases)) }
                }

                if (value.content.isStale) {
                    item {
                        Text(
                            text = stringResource(Res.string.whats_new_cached),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Content(val content: WhatsNewContent) : LoadState
}

@Composable
private fun CurrentVersionCard(
    version: String,
    channelLabel: String,
    lastCheckedLabel: String,
    status: String,
) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Icon(
                imageVector = Icons.Rounded.NewReleases,
                contentDescription = null,
                modifier = Modifier.size(NuvioTokens.Icon.lg),
                tint = MaterialTheme.nuvio.colors.accent,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Text(
                    text = stringResource(Res.string.whats_new_current_version),
                    color = MaterialTheme.nuvio.colors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(Res.string.whats_new_release_format, version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.whats_new_channel_format, channelLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                Text(
                    text = lastCheckedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun versionStatus(
    snapshot: WhatsNewSnapshot,
    channelLabel: String,
): String {
    val latest = snapshot.releases.firstOrNull()
        ?: return stringResource(Res.string.whats_new_status_unknown)

    val latestIsNewer = VersionUtils.isRemoteNewer(latest.version, snapshot.currentVersion)
    val currentIsNewer = VersionUtils.isRemoteNewer(snapshot.currentVersion, latest.version)

    return when {
        latestIsNewer -> {
            val count = snapshot.releases.count {
                VersionUtils.isRemoteNewer(it.version, snapshot.currentVersion)
            }.coerceAtLeast(1)
            if (count == 1) {
                stringResource(Res.string.whats_new_status_update_available)
            } else {
                stringResource(Res.string.whats_new_status_updates_available, count)
            }
        }
        currentIsNewer -> stringResource(
            Res.string.whats_new_status_current_ahead,
            channelLabel,
        )
        VersionUtils.parse(snapshot.currentVersion) != null &&
            VersionUtils.parse(latest.version) != null -> stringResource(Res.string.whats_new_status_up_to_date)
        else -> stringResource(Res.string.whats_new_status_unknown)
    }
}

@Composable
private fun lastCheckedLabel(fetchedAtMillis: Long): String {
    val ageMillis = (
        AppUpdaterPlatform.currentTimeMillis() - fetchedAtMillis
        ).coerceAtLeast(0L)
    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    return when {
        ageMillis < minuteMillis -> stringResource(Res.string.whats_new_last_checked_just_now)
        ageMillis < hourMillis -> stringResource(
            Res.string.whats_new_last_checked_minutes,
            ageMillis / minuteMillis,
        )
        ageMillis < dayMillis -> stringResource(
            Res.string.whats_new_last_checked_hours,
            ageMillis / hourMillis,
        )
        else -> stringResource(
            Res.string.whats_new_last_checked_days,
            ageMillis / dayMillis,
        )
    }
}

@Composable
private fun ReleaseCard(release: WhatsNewRelease) {
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }

    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.whats_new_latest_release),
            )
            Text(
                text = release.version,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            release.title.takeUnless { it == release.version }?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
            }
            releaseDate(release)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            ReleaseNotes(
                release = release,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                allowToggle = release.notes.size > MAX_PREVIEW_NOTES,
            )
        }
    }
}

@Composable
private fun ReleaseHistoryRow(release: WhatsNewRelease) {
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
    val expansionLabel = stringResource(
        if (expanded) Res.string.action_collapse else Res.string.action_expand,
    )

    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = expansionLabel
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.version,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                    release.title.takeUnless { it == release.version }?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                    releaseDate(release)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = expansionLabel,
                    modifier = Modifier
                        .size(NuvioTokens.Icon.md)
                        .rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            AnimatedVisibility(visible = expanded) {
                ReleaseNotes(
                    release = release,
                    expanded = true,
                    onToggle = {},
                    allowToggle = false,
                )
            }
        }
    }
}

@Composable
private fun ReleaseNotes(
    release: WhatsNewRelease,
    expanded: Boolean,
    onToggle: () -> Unit,
    allowToggle: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        val visibleNotes = if (expanded) release.notes else release.notes.take(MAX_PREVIEW_NOTES)

        if (visibleNotes.isEmpty()) {
            Text(
                text = stringResource(Res.string.whats_new_no_release_notes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
        } else {
            visibleNotes.forEach { note ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                ) {
                    Text(
                        text = stringResource(noteCategoryResource(note.category)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                    Text(
                        text = "• " + note.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                    note.authorLogin?.let { author ->
                        Text(
                            text = stringResource(
                                Res.string.whats_new_contributor_credit,
                                author,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                }
            }

            if (allowToggle) {
                TextButton(onClick = onToggle) {
                    Text(
                        text = if (expanded) {
                            stringResource(Res.string.whats_new_hide_changes)
                        } else {
                            stringResource(
                                Res.string.whats_new_show_all_changes,
                                release.notes.size,
                            )
                        },
                    )
                }
            }
        }

        release.releaseUrl?.let { url ->
            val uriHandler = LocalUriHandler.current
            NuvioPrimaryButton(
                text = stringResource(Res.string.whats_new_open_github),
                onClick = { uriHandler.openUri(url) },
            )
        }
    }
}

private fun noteCategoryResource(category: WhatsNewNoteCategory) = when (category) {
    WhatsNewNoteCategory.FEATURES -> Res.string.whats_new_category_features
    WhatsNewNoteCategory.FIXES -> Res.string.whats_new_category_fixes
    WhatsNewNoteCategory.PERFORMANCE -> Res.string.whats_new_category_performance
    WhatsNewNoteCategory.LOCALIZATION -> Res.string.whats_new_category_localization
    WhatsNewNoteCategory.OTHER -> Res.string.whats_new_category_other
}

private fun releaseDate(release: WhatsNewRelease): String? =
    release.publishedAt?.takeIf { it.length >= 10 }?.let { raw ->
        val year = raw.substring(0, 4)
        val month = raw.substring(5, 7).toIntOrNull() ?: return@let raw.take(10)
        val day = raw.substring(8, 10).toIntOrNull() ?: return@let raw.take(10)
        val monthName = when (month) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mar"
            4 -> "Apr"
            5 -> "May"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Oct"
            11 -> "Nov"
            12 -> "Dec"
            else -> return@let raw.take(10)
        }
        "$day $monthName $year"
    }
