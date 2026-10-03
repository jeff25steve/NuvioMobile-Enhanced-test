package com.nuvio.app.features.whatsnew

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.whats_new_empty
import nuvio.composeapp.generated.resources.whats_new_load_more
import nuvio.composeapp.generated.resources.whats_new_last_checked
import nuvio.composeapp.generated.resources.whats_new_loading
import nuvio.composeapp.generated.resources.whats_new_more_changes
import nuvio.composeapp.generated.resources.whats_new_refresh
import nuvio.composeapp.generated.resources.whats_new_since_version
import nuvio.composeapp.generated.resources.whats_new_title
import nuvio.composeapp.generated.resources.whats_new_up_to_date
import nuvio.composeapp.generated.resources.whats_new_view_on_github
import org.jetbrains.compose.resources.stringResource

@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val state by WhatsNewRepository.uiState.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        WhatsNewRepository.load()
    }

    NuvioScreen(modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.whats_new_title),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { scope.launch { WhatsNewRepository.load(forceRefresh = true) } },
                        enabled = !state.isRefreshing,
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.whats_new_refresh),
                        )
                    }
                },
            )
        }

        if (state.isLoading && state.releases.isEmpty()) {
            item { LoadingContent() }
        } else if (state.releases.isEmpty()) {
            item {
                ErrorContent(
                    message = state.errorMessage ?: stringResource(Res.string.whats_new_empty),
                    onRetry = { scope.launch { WhatsNewRepository.load(forceRefresh = true) } },
                )
            }
        } else {
            val newer = state.releases.filter { it.isNewerThanCurrent }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (newer.isEmpty()) {
                            stringResource(Res.string.whats_new_up_to_date)
                        } else {
                            stringResource(
                                Res.string.whats_new_since_version,
                                AppVersionConfig.VERSION_NAME,
                            )
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "v${AppVersionConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.lastCheckedEpochMs?.let {
                        Text(
                            text = stringResource(
                                Res.string.whats_new_last_checked,
                                formatLastChecked(it),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (newer.isNotEmpty()) {
                items(newer, key = { "since-${it.tag}" }) { release ->
                    ReleaseCard(
                        release = release,
                        expanded = expanded[release.tag] == true,
                        onToggle = { expanded[release.tag] = expanded[release.tag] != true },
                        onOpenGitHub = release.url?.let { url -> { uriHandler.openUri(url) } },
                    )
                }
            }

            val history = state.releases.filterNot { it.isNewerThanCurrent }
            item {
                Text(
                    text = "Recent history",
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(state.releases, key = { "history-${it.tag}" }) { release ->
                ReleaseCard(
                    release = release,
                    expanded = expanded[release.tag] == true,
                    onToggle = { expanded[release.tag] = expanded[release.tag] != true },
                    onOpenGitHub = release.url?.let { url -> { uriHandler.openUri(url) } },
                )
            }

            if (state.hasMore) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator()
                        } else {
                            AssistChip(
                                onClick = { scope.launch { WhatsNewRepository.loadMore() } },
                                label = { Text(stringResource(Res.string.whats_new_load_more)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: WhatsNewRelease,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenGitHub: (() -> Unit)?,
) {
    val previewCount = 5
    val visibleNotes = if (expanded) release.notes else release.notes.take(previewCount)

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        onClick = onToggle,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    release.publishedAt?.let {
                        Text(
                            text = formatReleaseDateForDisplay(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (release.isPrerelease) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Beta") })
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            }

            if (visibleNotes.isEmpty()) {
                Text(
                    text = "No release notes were provided.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visibleNotes.forEach { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = "• ${note.text}", style = MaterialTheme.typography.bodyMedium)
                        note.contributor?.let {
                            Text(
                                text = "@$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                if (!expanded && release.notes.size > previewCount) {
                    Text(
                        text = stringResource(
                            Res.string.whats_new_more_changes,
                            release.notes.size - previewCount,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            AnimatedVisibility(visible = expanded && onOpenGitHub != null) {
                Column {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.whats_new_view_on_github),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onOpenGitHub?.invoke() }) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.whats_new_loading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        AssistChip(
            onClick = onRetry,
            label = { Text(stringResource(Res.string.action_retry)) },
        )
    }
}


private fun formatLastChecked(epochMs: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
    return formatReleaseDateForDisplay(date.toString())
}
