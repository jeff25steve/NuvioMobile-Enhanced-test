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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.UpdatePreferences
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_whats_new
import nuvio.composeapp.generated.resources.whats_new_cached
import nuvio.composeapp.generated.resources.whats_new_current_version
import nuvio.composeapp.generated.resources.whats_new_latest_release
import nuvio.composeapp.generated.resources.whats_new_load_failed
import nuvio.composeapp.generated.resources.whats_new_no_release_notes
import nuvio.composeapp.generated.resources.whats_new_no_releases
import nuvio.composeapp.generated.resources.whats_new_open_github
import nuvio.composeapp.generated.resources.whats_new_recent_releases
import nuvio.composeapp.generated.resources.whats_new_release_format
import nuvio.composeapp.generated.resources.whats_new_retry
import nuvio.composeapp.generated.resources.whats_new_since_one_release
import nuvio.composeapp.generated.resources.whats_new_since_releases
import nuvio.composeapp.generated.resources.whats_new_unavailable
import nuvio.composeapp.generated.resources.whats_new_up_to_date
import org.jetbrains.compose.resources.stringResource

@Composable
fun WhatsNewSettingsScreen(onBack: () -> Unit) {
    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_whats_new),
                onBack = onBack,
            )
        }
        whatsNewSettingsContent(isTablet = false)
    }
}

internal fun LazyListScope.whatsNewSettingsContent(isTablet: Boolean) {
    item { WhatsNewPageBody(isTablet = isTablet) }
}

@Composable
private fun WhatsNewPageBody(isTablet: Boolean) {
    val channel by UpdatePreferences.shared.channel.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var retryKey by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(channel, retryKey) {
        state = LoadState.Loading
        state = WhatsNewRepository.load(
            channel = channel,
            currentVersion = AppVersionConfig.VERSION_NAME,
        ).fold(
            onSuccess = LoadState::Content,
            onFailure = { LoadState.Error },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 14.dp),
    ) {
        when (val value = state) {
            LoadState.Loading -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NuvioLoadingIndicator()
            }

            LoadState.Error -> NuvioSurfaceCard {
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
                        text = stringResource(Res.string.whats_new_retry),
                        onClick = { retryKey++ },
                    )
                }
            }

            is LoadState.Content -> {
                val snapshot = value.content.snapshot
                val latest = snapshot.releases.firstOrNull()

                CurrentVersionCard(snapshot.currentVersion)

                if (snapshot.hasCompleteSinceVersion) {
                    Text(
                        text = when (snapshot.sinceYourVersion.size) {
                            0 -> stringResource(Res.string.whats_new_up_to_date)
                            1 -> stringResource(Res.string.whats_new_since_one_release)
                            else -> stringResource(
                                Res.string.whats_new_since_releases,
                                snapshot.sinceYourVersion.size,
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                latest?.let { ReleaseCard(it) }

                if (snapshot.releases.size > 1) {
                    NuvioSectionLabel(
                        text = stringResource(Res.string.whats_new_recent_releases),
                    )
                    snapshot.releases.drop(1).forEach { release ->
                        ReleaseHistoryRow(release)
                    }
                }

                if (snapshot.releases.isEmpty()) {
                    Text(text = stringResource(Res.string.whats_new_no_releases))
                }

                if (value.content.isStale) {
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

private sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Content(val content: WhatsNewContent) : LoadState
}

@Composable
private fun CurrentVersionCard(version: String) {
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
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
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
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: WhatsNewRelease) {
    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            NuvioSectionLabel(
                text = stringResource(Res.string.whats_new_latest_release),
            )
            Text(
                text = release.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            releaseDate(release)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            ReleaseNotes(release)
        }
    }
}

@Composable
private fun ReleaseHistoryRow(release: WhatsNewRelease) {
    var expanded by remember { mutableStateOf(false) }

    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        maxLines = 2,
                    )
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
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                    tint = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            AnimatedVisibility(visible = expanded) {
                ReleaseNotes(release)
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: WhatsNewRelease) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        Text(
            text = release.notes.ifBlank { stringResource(Res.string.whats_new_no_release_notes) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        release.releaseUrl?.let { url ->
            NuvioPrimaryButton(
                text = stringResource(Res.string.whats_new_open_github),
                onClick = { uriHandler.openUri(url) },
            )
        }
    }
}

private fun releaseDate(release: WhatsNewRelease): String? =
    release.publishedAt?.takeIf { it.length >= 10 }?.take(10)
