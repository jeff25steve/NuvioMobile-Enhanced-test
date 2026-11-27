package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.PosterRowImagePrefetchEffect
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeCatalogPosterShuffleSession
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.homeCatalogTitleColorArgb
import com.nuvio.app.features.home.orderCatalogPosters
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.rememberSubtitleFontFamily
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.watching.application.WatchingState

@Composable
fun HomeCatalogRowSection(
    section: HomeCatalogSection,
    modifier: Modifier = Modifier,
    entries: List<MetaPreview> = section.items,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
    sectionPadding: Dp? = null,
    onViewAllClick: (() -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    shufflePosters: Boolean = false,
) {
    if (sectionPadding != null) {
        HomeCatalogRowSectionContent(
            section = section,
            entries = entries,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            modifier = modifier.fillMaxWidth(),
            sectionPadding = sectionPadding,
            onViewAllClick = onViewAllClick,
            onPosterClick = onPosterClick,
            onPosterLongClick = onPosterLongClick,
            shufflePosters = shufflePosters,
        )
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            HomeCatalogRowSectionContent(
                section = section,
                entries = entries,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                modifier = Modifier.fillMaxWidth(),
                sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value),
                onViewAllClick = onViewAllClick,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
                shufflePosters = shufflePosters,
            )
        }
    }
}

@Composable
private fun HomeCatalogRowSectionContent(
    section: HomeCatalogSection,
    entries: List<MetaPreview>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    modifier: Modifier,
    sectionPadding: Dp,
    onViewAllClick: (() -> Unit)?,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    shufflePosters: Boolean,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val rowState = rememberLazyListState()
    val sessionSeed = if (shufflePosters) HomeCatalogPosterShuffleSession.seed else 0L
    val displayEntries = remember(entries, section.key, shufflePosters, sessionSeed) {
        orderCatalogPosters(
            items = entries,
            catalogKey = section.key,
            sessionSeed = sessionSeed,
            enabled = shufflePosters,
            stableKey = { item -> item.stableKey() },
        )
    }
    val titleStyle = section.titleStyle.normalized()
    val titleTokens = MaterialTheme.nuvio.colors
    val darkBackground = titleTokens.textPrimary.luminance() > 0.5f
    val titleColor = homeCatalogTitleColorArgb(titleStyle.colorId, darkBackground)?.let { Color(it) }
    val titleTextStyle = if (titleStyle.isDefault) {
        null
    } else {
        val fontFamily = if (titleStyle.fontId == "default") {
            MaterialTheme.typography.titleLarge.fontFamily
        } else {
            rememberSubtitleFontFamily(titleStyle.fontId)
        }
        MaterialTheme.typography.titleLarge.copy(
            fontFamily = fontFamily,
            fontSize = titleStyle.fontSizeSp.sp,
            fontWeight = if (titleStyle.bold) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
    val prefetchTargets = remember(displayEntries, posterCardStyle) {
        displayEntries.map { item ->
            item.homePosterImages(posterCardStyle, useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled)
                .toPrefetchTarget()
        }
    }
    PosterRowImagePrefetchEffect(state = rowState, targets = prefetchTargets)

    NuvioShelfSection(
        title = section.title,
        titleTextStyle = titleTextStyle,
        titleColor = titleColor,
        entries = displayEntries,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        rowModifier = Modifier.testTag("nuvio_benchmark_catalog_row"),
        onViewAllClick = onViewAllClick,
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { item -> item.stableKey() },
        state = rowState,
    ) { item ->
        HomePosterCard(
            item = item,
            useLandscapeBackdropMode = posterCardStyle.catalogLandscapeModeEnabled,
            posterCardStyle = posterCardStyle,
            isWatched = WatchingState.isPosterWatched(
                watchedKeys = watchedKeys,
                item = item,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            ),
            onClick = onPosterClick?.let { { it(item) } },
            onLongClick = onPosterLongClick?.let { { it(item) } },
        )
    }
}
