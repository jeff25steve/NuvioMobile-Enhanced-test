package com.nuvio.app.features.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
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

    val horizontalPadding = if (isTablet) 20.dp else 0.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.whats_new_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.whats_new_load_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { retryKey++ }) {
                        Text(text = stringResource(Res.string.whats_new_retry))
                    }
                }
            }

            is LoadState.Content -> {
                val snapshot = value.content.snapshot
                val latest = snapshot.releases.firstOrNull()

                CurrentVersionCard(snapshot.currentVersion)

                if (snapshot.hasCompleteSinceVersion) {
                    Text(
                        text = if (snapshot.sinceYourVersion.size == 1) {
                            stringResource(Res.string.whats_new_since_one_release)
                        } else {
                            stringResource(
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
                    Text(
                        text = stringResource(Res.string.whats_new_recent_releases),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.NewReleases,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(Res.string.whats_new_current_version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(Res.string.whats_new_release_format, version),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: WhatsNewRelease) {
    NuvioSurfaceCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.whats_new_latest_release),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = release.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            releaseDate(release)?.let { Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ReleaseNotes(release)
        }
    }
}

@Composable
private fun ReleaseHistoryRow(release: WhatsNewRelease) {
    var expanded by remember { mutableStateOf(false) }

    NuvioSurfaceCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = release.title, fontWeight = FontWeight.SemiBold)
                    releaseDate(release)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(text = release.version)
                }
            }
            if (expanded) ReleaseNotes(release)
        }
    }
}

@Composable
private fun ReleaseNotes(release: WhatsNewRelease) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = release.notes.ifBlank { stringResource(Res.string.whats_new_no_release_notes) },
            color = MaterialTheme.colorScheme.onSurface,
        )
        release.releaseUrl?.let { url ->
            Button(onClick = { uriHandler.openUri(url) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(Res.string.whats_new_open_github),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun releaseDate(release: WhatsNewRelease): String? =
    release.publishedAt?.takeIf { it.length >= 10 }?.take(10)
