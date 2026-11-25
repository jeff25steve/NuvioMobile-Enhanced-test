package com.nuvio.app.features.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.player.seekpreview.SeekPreviewController
import com.nuvio.app.features.player.seekpreview.SeekPreviewOverlay
import com.nuvio.app.features.streams.StreamBadge
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.i18n.localizedEpisodeTitle

internal val PlayerTimelineContentInset = 2.dp

internal val PlayerChapterMarkWidth = 2.dp

internal val PlayerChapterMarkColor = Color.Black.copy(alpha = 0.6f)

@Composable
internal fun PlayerTimelineDetails(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    releaseInfo: String?,
    metrics: PlayerLayoutMetrics,
    badges: List<StreamBadge> = emptyList(),
    modifier: Modifier = Modifier,
    titleLogo: String? = null,
    showTitleLogo: Boolean = false,
    animeEnhancement: AnimeEnhancementStatus? = null,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    Column(modifier.padding(horizontal = PlayerTimelineContentInset)) {
        PlayerTitleArtwork(
            title = title,
            logoUrl = titleLogo,
            showLogo = showTitleLogo,
            textStyle = typeScale.titleLg.copy(
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * 1.16f,
                fontWeight = FontWeight.SemiBold,
            ),
            textColor = Color.White,
            maxLines = 1,
            maxLogoHeight = metrics.timelineLogoMaxHeight,
            maxLogoWidth = metrics.logoMaxWidth,
        )
        if (seasonNumber != null && episodeNumber != null) {
            val episodeCode = stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
            val displayEpisodeTitle = localizedEpisodeTitle(episodeTitle, episodeNumber)
            Text(
                text = if (displayEpisodeTitle.isNullOrBlank()) episodeCode else "$episodeCode • $displayEpisodeTitle",
                style = typeScale.bodyMd.copy(fontSize = metrics.episodeInfoSize),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badges.isNotEmpty()) {
            PlayerBadges(badges = badges, modifier = Modifier.padding(vertical = 4.dp))
        }
        AnimeEnhancementBadge(status = animeEnhancement, modifier = Modifier.padding(vertical = 4.dp))
        if (!releaseInfo.isNullOrBlank()) {
            Text(
                text = releaseInfo,
                style = typeScale.labelSm.copy(fontSize = metrics.metadataSize),
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ReportTimelineTouch(touching: Boolean, onTouchChange: (Boolean) -> Unit) {
    val latestOnTouchChange by rememberUpdatedState(onTouchChange)
    LaunchedEffect(touching) { latestOnTouchChange(touching) }
    DisposableEffect(Unit) {
        onDispose { latestOnTouchChange(false) }
    }
}

internal fun shouldEnablePlayerScrubbing(
    isDragged: Boolean,
    enabled: Boolean,
    durationMs: Long,
): Boolean = isDragged && enabled && durationMs > 0L

internal fun fractionalSeekToleranceForTrack(widthPx: Int, density: Float): Double? {
    if (widthPx <= 0 || !density.isFinite() || density <= 0f) return null
    return (2f * density / widthPx).coerceIn(0f, 1f).toDouble()
}

internal fun interpolatePlaybackPositionMs(
    positionMs: Long,
    elapsedNanos: Long,
    playbackSpeed: Float,
    durationMs: Long,
): Long {
    val basePositionMs = positionMs.coerceAtLeast(0L)
    val boundedDurationMs = durationMs.takeIf { it > 0L }
    if (elapsedNanos <= 0L || !playbackSpeed.isFinite() || playbackSpeed <= 0f) {
        return boundedDurationMs?.let(basePositionMs::coerceAtMost) ?: basePositionMs
    }

    val elapsedMs = elapsedNanos.toDouble() / 1_000_000.0 * playbackSpeed.toDouble()
    val candidatePositionMs = (basePositionMs.toDouble() + elapsedMs)
        .coerceAtMost(Long.MAX_VALUE.toDouble())
        .toLong()
    return boundedDurationMs?.let(candidatePositionMs::coerceAtMost) ?: candidatePositionMs
}

@Composable
internal fun rememberFrameSynchronizedPositionMs(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isInteracting: Boolean,
): Long {
    val shouldAnimate = isPlaying &&
        !isInteracting &&
        durationMs > 0L &&
        playbackSpeed.isFinite() &&
        playbackSpeed > 0f

    val framePositionMs by produceState(
        initialValue = timelinePositionInBounds(positionMs, durationMs),
        positionMs,
        durationMs,
        isPlaying,
        playbackSpeed,
        isInteracting,
    ) {
        value = timelinePositionInBounds(positionMs, durationMs)
        if (!shouldAnimate) return@produceState

        var frameAnchorNanos: Long? = null
        while (true) {
            withFrameNanos { frameTimeNanos ->
                val anchorNanos = frameAnchorNanos
                    ?: frameTimeNanos.also { frameAnchorNanos = it }
                value = interpolatePlaybackPositionMs(
                    positionMs = positionMs,
                    elapsedNanos = (frameTimeNanos - anchorNanos).coerceAtLeast(0L),
                    playbackSpeed = playbackSpeed,
                    durationMs = durationMs,
                )
            }
        }
    }
    return if (isInteracting) {
        timelinePositionInBounds(positionMs, durationMs)
    } else {
        framePositionMs
    }
}

private fun timelinePositionInBounds(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)


@Composable
internal fun ReportTimelineScrubbing(
    scrubbing: Boolean,
    fractionalSeekTolerance: Double?,
    onScrubbingChange: (Boolean, Double?) -> Unit,
) {
    val latestOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    LaunchedEffect(scrubbing, fractionalSeekTolerance) {
        latestOnScrubbingChange(scrubbing, fractionalSeekTolerance)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnScrubbingChange(false, null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTimeline(
    snapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    seekPreview: SeekPreviewController? = null,
    onTouchChange: (Boolean) -> Unit = {},
    onScrubbingChange: (Boolean, Double?) -> Unit = { _, _ -> },
    /** Each chapter after the first gets a mark where it starts. */
    chapters: List<PlayerChapter> = emptyList(),
) {
    val durationMs = snapshot.durationMs.coerceAtLeast(0L)
    val rangeEnd = durationMs.coerceAtLeast(1L).toFloat()
    val bufferedFraction = playerBufferedFraction(snapshot.bufferedPositionMs, durationMs)
    val chapterMarks = remember(chapters, durationMs) {
        chapters.chapterMarkFractions(durationMs)
    }
    val accentBrush = MaterialTheme.themePalette.accentBrush()
    val description = stringResource(Res.string.player_seek_position)
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    var trackWidthPx by remember { mutableStateOf(0) }
    val fractionalSeekTolerance = fractionalSeekToleranceForTrack(
        trackWidthPx,
        LocalDensity.current.density,
    )
    val isInteracting = enabled && durationMs > 0L && (isPressed || isDragged || scrubPosition != null)
    ReportTimelineTouch(isInteracting, onTouchChange)
    ReportTimelineScrubbing(
        scrubbing = shouldEnablePlayerScrubbing(isDragged, enabled, durationMs),
        fractionalSeekTolerance = fractionalSeekTolerance,
        onScrubbingChange = onScrubbingChange,
    )
    val trackThickness by animateDpAsState(
        targetValue = if (isInteracting) 10.dp else 6.dp,
        animationSpec = tween(durationMillis = if (isInteracting) 140 else 180),
        label = "playerTimelineThickness",
    )

    val framePositionMs = rememberFrameSynchronizedPositionMs(
        positionMs = displayedPositionMs,
        durationMs = durationMs,
        isPlaying = snapshot.isPlaying,
        playbackSpeed = snapshot.playbackSpeed,
        isInteracting = isInteracting,
    )
    val sliderState = remember(rangeEnd) { SliderState(trackRange = 0f..rangeEnd) }
    sliderState.value = framePositionMs.coerceIn(0L, durationMs).toFloat()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box {
            Slider(
                state = sliderState,
                onValueChange = { value ->
                    val position = value.toLong().coerceIn(0L, durationMs)
                    scrubPosition = position
                    onScrubChange(position)
                },
                onValueChangeFinished = {
                    onScrubFinished((scrubPosition ?: framePositionMs).coerceIn(0L, durationMs))
                    scrubPosition = null
                },
                enabled = enabled && durationMs > 0L,
                interactionSource = interactionSource,
                thumb = {},
                track = { state ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .drawBehind {
                                val trackHeight = trackThickness.toPx()
                                val trackOrigin = Offset(0f, 35.dp.toPx() - trackHeight / 2)
                                val radius = CornerRadius(trackHeight / 2)
                                drawRoundRect(
                                    color = PlayerBaseTrackColor,
                                    topLeft = trackOrigin,
                                    size = Size(size.width, trackHeight),
                                    cornerRadius = radius,
                                )
                                drawRoundRect(
                                    color = PlayerBufferedTrackColor,
                                    topLeft = trackOrigin,
                                    size = Size(size.width * bufferedFraction, trackHeight),
                                    cornerRadius = radius,
                                )
                                drawRoundRect(
                                    brush = accentBrush,
                                    topLeft = trackOrigin,
                                    size = Size(size.width * (state.value / rangeEnd).coerceIn(0f, 1f), trackHeight),
                                    cornerRadius = radius,
                                )
                                val markWidth = PlayerChapterMarkWidth.toPx()
                                chapterMarks.forEach { fraction ->
                                    drawRect(
                                        color = PlayerChapterMarkColor,
                                        topLeft = Offset(size.width * fraction - markWidth / 2f, trackOrigin.y),
                                        size = Size(markWidth, trackHeight),
                                    )
                                }
                            },
                    )
                },
                modifier = modifier
                    .fillMaxWidth()
                    .onSizeChanged { trackWidthPx = it.width }
                    .height(24.dp)
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .requiredHeight(48.dp)
                    .semantics { contentDescription = description },
            )
            SeekPreviewOverlay(
                controller = seekPreview,
                active = isInteracting,
                positionMs = framePositionMs.coerceIn(0L, durationMs),
                durationMs = durationMs,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The name of the chapter at [positionMs], or "Chapter N" when the file gives it none. Hidden when
 * "Show Chapter Title" is off.
 */
@Composable
internal fun PlayerChapterTitle(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val settings by PlayerSettingsRepository.uiState.collectAsState()
    if (!settings.chapterTitleEnabled) return
    val index = PlayerChapters.indexAt(chapters, positionMs)
    if (index < 0) return
    Text(
        text = chapters[index].title ?: stringResource(Res.string.compose_player_chapter_number, index + 1),
        style = MaterialTheme.nuvioTypeScale.bodyMd,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = PlayerTimelineContentInset),
    )
}
