package com.nuvio.app.features.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TOP_SUBTITLE_INSET_FRACTION = 0.08f
private const val SIDE_SUBTITLE_INSET_FRACTION = 0.05f

@Composable
internal fun StyledSubtitleOverlay(
    cues: StyledSubtitleCues,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    videoAspectRatio: Float = 0f,
    videoResizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
) {
    val fontFamily = rememberSubtitleFontFamily(style.appearance.fontId)
    if (cues.isEmpty) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fittedVideoBounds = subtitleVideoBoundsPx(
            containerWidthPx = with(density) { maxWidth.toPx() },
            containerHeightPx = with(density) { maxHeight.toPx() },
            videoAspectRatio = videoAspectRatio,
            resizeMode = videoResizeMode,
        )
        val scale = (maxHeight / 360.dp).coerceAtMost(1f)
        val bottomInset = maxHeight * (0.0533f + style.bottomOffset.coerceIn(0, 200) / 1000f)
        val topInset = maxHeight * TOP_SUBTITLE_INSET_FRACTION
        val sideInset = maxWidth * SIDE_SUBTITLE_INSET_FRACTION
        cues.blocks.forEach { block ->
            PositionedSubtitleBlock(
                block = block,
                style = style,
                scale = scale,
                fontFamily = fontFamily,
                videoWidthPx = fittedVideoBounds.width,
                videoHeightPx = fittedVideoBounds.height,
                topInset = topInset,
                bottomInset = bottomInset,
                sideInset = sideInset,
            )
        }
    }
}

@Composable
private fun PositionedSubtitleBlock(
    block: StyledSubtitleBlock,
    style: SubtitleStyleState,
    scale: Float,
    fontFamily: FontFamily,
    videoWidthPx: Float,
    videoHeightPx: Float,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    sideInset: androidx.compose.ui.unit.Dp,
) {
    val density = LocalDensity.current
    val horizontalInset = 16.dp * scale +
        if (block.horizontal == SubtitleHorizontalPosition.CENTER) 0.dp else sideInset
    val horizontalInsetPx = with(density) { horizontalInset.roundToPx() }
    val topInsetPx = with(density) { topInset.roundToPx() }
    val bottomInsetPx = with(density) { bottomInset.roundToPx() }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            StyledSubtitleText(
                text = block.text,
                style = style,
                scale = scale,
                videoWidthPx = videoWidthPx,
                videoHeightPx = videoHeightPx,
                fontFamily = fontFamily,
                horizontal = if (block.fillCueWidth) block.textAlignment else SubtitleHorizontalPosition.CENTER,
                textAlignment = block.textAlignment,
                fillCueWidth = block.fillCueWidth,
            )
        },
    ) { measurables, constraints ->
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val requestedWidth = block.widthFraction
            ?.let { (viewportWidth * it.coerceIn(0f, 1f)).roundToInt() }
            ?: (viewportWidth - horizontalInsetPx * 2)
        if (requestedWidth <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            layout(viewportWidth, viewportHeight) {}
        } else {
            val placeable = measurables.single().measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = requestedWidth.coerceAtMost(viewportWidth),
                    minHeight = 0,
                ),
            )
            val x = block.positionX?.let { position ->
                subtitleCueAxisOffset(
                    viewportSize = viewportWidth,
                    contentSize = placeable.width,
                    positionFraction = position,
                    anchorFraction = block.positionXAnchor,
                )
            } ?: when (block.horizontal) {
                SubtitleHorizontalPosition.START -> horizontalInsetPx
                SubtitleHorizontalPosition.CENTER -> (viewportWidth - placeable.width) / 2
                SubtitleHorizontalPosition.END -> viewportWidth - placeable.width - horizontalInsetPx
            }
            val y = block.positionY?.let { position ->
                subtitleCueAxisOffset(
                    viewportSize = viewportHeight,
                    contentSize = placeable.height,
                    positionFraction = position,
                    anchorFraction = block.positionYAnchor,
                )
            } ?: when (block.vertical) {
                SubtitleVerticalPosition.TOP -> topInsetPx
                SubtitleVerticalPosition.MIDDLE -> (viewportHeight - placeable.height) / 2
                SubtitleVerticalPosition.BOTTOM -> viewportHeight - placeable.height - bottomInsetPx
            }
            layout(viewportWidth, viewportHeight) {
                placeable.place(
                    x = x.coerceIn(0, (viewportWidth - placeable.width).coerceAtLeast(0)),
                    y = y.coerceIn(0, (viewportHeight - placeable.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

@Composable
internal fun StyledSubtitleText(
    text: String,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    videoWidthPx: Float = SUBTITLE_BLUR_REFERENCE_WIDTH_PX,
    videoHeightPx: Float = SUBTITLE_BLUR_REFERENCE_HEIGHT_PX,
    fontFamily: FontFamily = rememberSubtitleFontFamily(style.appearance.fontId),
    horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER,
    textAlignment: SubtitleHorizontalPosition = horizontal,
    fillCueWidth: Boolean = true,
) {
    StyledSubtitleText(
        text = AnnotatedString(text),
        style = style,
        modifier = modifier,
        scale = scale,
        videoWidthPx = videoWidthPx,
        videoHeightPx = videoHeightPx,
        fontFamily = fontFamily,
        horizontal = horizontal,
        textAlignment = textAlignment,
        fillCueWidth = fillCueWidth,
    )
}

@Composable
internal fun StyledSubtitleText(
    text: AnnotatedString,
    style: SubtitleStyleState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    videoWidthPx: Float = SUBTITLE_BLUR_REFERENCE_WIDTH_PX,
    videoHeightPx: Float = SUBTITLE_BLUR_REFERENCE_HEIGHT_PX,
    fontFamily: FontFamily = rememberSubtitleFontFamily(style.appearance.fontId),
    horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER,
    textAlignment: SubtitleHorizontalPosition = horizontal,
    fillCueWidth: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(if (fillCueWidth) modifier.fillMaxWidth() else modifier) {
        val paddingX = with(density) { (8.dp * scale).toPx() }
        val paddingY = with(density) { (4.dp * scale).toPx() }
        val availableWidth = constraints.maxWidth.toFloat()

        val outline = with(density) { (style.outlineWidth.dp * scale).toPx() } / 2f
        val outlineEnabled = style.outlineEnabled && outline > 0f
        val outlineBlur = style.appearance.outlineBlur.coerceIn(
            SUBTITLE_OUTLINE_BLUR_MIN,
            SUBTITLE_OUTLINE_BLUR_MAX,
        )
        val outlineBlurSigmaXPx = subtitleOutlineBlurSigmaXPx(outlineBlur, videoWidthPx)
        val outlineBlurSigmaYPx = subtitleOutlineBlurSigmaYPx(outlineBlur, videoHeightPx)
        val outlineBlurSigmaXDp = with(density) { outlineBlurSigmaXPx.toDp() }
        val outlineBlurSigmaYDp = with(density) { outlineBlurSigmaYPx.toDp() }
        val legacyBlurRadiusPx = sqrt(outlineBlurSigmaXPx * outlineBlurSigmaYPx)
        val useNativeBlur = supportsNativeSubtitleBlur()
        val blurBleedPx = if (outlineEnabled && outlineBlur > 0) {
            maxOf(outlineBlurSigmaXPx, outlineBlurSigmaYPx) * SUBTITLE_BLUR_OUTSET_SIGMAS
        } else {
            0f
        }
        val edgePaddingPx = if (outlineEnabled) outline + blurBleedPx else 0f
        val edgePadding = with(density) { edgePaddingPx.toDp() }

        val textStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = (style.fontSizeSp * scale).sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (textAlignment) {
                SubtitleHorizontalPosition.START -> TextAlign.Start
                SubtitleHorizontalPosition.CENTER -> TextAlign.Center
                SubtitleHorizontalPosition.END -> TextAlign.End
            },
            textDirection = TextDirection.ContentOrLtr,
        )
        val availableTextWidth = (availableWidth - paddingX * 2 - edgePaddingPx * 2).coerceAtLeast(1f).toInt()
        val textConstraints = Constraints(maxWidth = availableTextWidth)
        val textAlpha = style.textColor.alpha
        val layout = measurer.measure(
            text = text.mapColors { subtitleFileColor(it, textAlpha) },
            style = textStyle,
            constraints = textConstraints,
        )
        val plainLayout = if (text.spanStyles.any { it.item.color.isSpecified }) {
            measurer.measure(text = text.mapColors { Color.Unspecified }, style = textStyle, constraints = textConstraints)
        } else {
            layout
        }
        val lineLeft = (0 until layout.lineCount).minOfOrNull { layout.getLineLeft(it) } ?: 0f
        val lineRight = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
        val (contentWidth, textInset) = when (horizontal) {
            SubtitleHorizontalPosition.CENTER -> (layout.size.width + paddingX * 2) to paddingX
            else -> (lineRight - lineLeft + paddingX * 2) to (paddingX - lineLeft)
        }
        val contentHeight = with(density) { (layout.size.height + paddingY * 2).toDp() }
        val boxWidthPx = contentWidth + edgePaddingPx * 2
        val boxWidth = with(density) { boxWidthPx.toDp() }
        val leftPx = if (!fillCueWidth) 0f else when (horizontal) {
            SubtitleHorizontalPosition.START -> 0f
            SubtitleHorizontalPosition.CENTER -> (availableWidth - boxWidthPx) / 2f
            SubtitleHorizontalPosition.END -> availableWidth - boxWidthPx
        }
        val left = with(density) { leftPx.toDp() }
        val origin = Offset(edgePaddingPx + textInset, paddingY + edgePaddingPx)
        val radius = with(density) { (style.appearance.backgroundRadius.dp * scale).toPx() }
        val shadowSize = with(density) { (style.appearance.shadowSize.dp * scale).toPx() }
        val shadowOpacity = style.appearance.shadowOpacity.coerceIn(0, 100) / 100f

        Box(
            Modifier.requiredWidth(boxWidth)
                .height(contentHeight + edgePadding * 2)
                .offset(x = left, y = edgePadding)
                .semantics { this.text = text },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = style.backgroundColor,
                    topLeft = Offset(edgePaddingPx, edgePaddingPx),
                    size = androidx.compose.ui.geometry.Size(
                        contentWidth,
                        (size.height - edgePaddingPx * 2).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(radius),
                )
            }

            if (style.appearance.shadowEnabled && shadowOpacity > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    drawText(
                        plainLayout,
                        color = Color.Transparent,
                        topLeft = origin,
                        drawStyle = Fill,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = shadowOpacity),
                            offset = Offset(shadowSize * 0.55f, shadowSize * 0.55f),
                            blurRadius = (shadowSize * 0.35f).coerceAtLeast(0.5f),
                        ),
                    )
                    drawText(
                        plainLayout,
                        color = Color.Transparent,
                        topLeft = origin,
                        drawStyle = Fill,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = shadowOpacity * 0.45f),
                            offset = Offset(shadowSize, shadowSize),
                            blurRadius = shadowSize * 1.8f,
                        ),
                    )
                }
            }

            if (outlineEnabled) {
                val outlineBlurOpacity = subtitleOutlineBlurOpacity(outlineBlur)
                val outlineColor = style.outlineColor.copy(
                    alpha = style.outlineColor.alpha * style.textColor.alpha * outlineBlurOpacity,
                )
                if (outlineBlur > 0) {
                    if (useNativeBlur) {
                        Canvas(
                            Modifier.fillMaxSize().blur(
                                radiusX = outlineBlurSigmaXDp,
                                radiusY = outlineBlurSigmaYDp,
                                edgeTreatment = BlurredEdgeTreatment.Unbounded,
                            ),
                        ) {
                            drawText(
                                plainLayout,
                                color = outlineColor,
                                topLeft = origin,
                                drawStyle = Stroke(outline * 2f),
                                shadow = Shadow.None,
                            )
                        }
                    } else {
                        val maskShadowColor = outlineColor.copy(
                            alpha = outlineColor.alpha.coerceAtMost(0.999f),
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            drawText(
                                plainLayout,
                                color = Color.Transparent,
                                topLeft = origin,
                                drawStyle = Stroke(outline * 2f),
                                shadow = Shadow(
                                    color = maskShadowColor,
                                    offset = Offset.Zero,
                                    blurRadius = legacyBlurRadiusPx,
                                ),
                            )
                        }
                    }
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        drawText(
                            plainLayout,
                            color = outlineColor,
                            topLeft = origin,
                            drawStyle = Stroke(outline * 2f),
                            shadow = Shadow.None,
                        )
                    }
                }
            }

            Canvas(Modifier.fillMaxSize()) {
                drawText(
                    layout,
                    color = style.textColor,
                    topLeft = origin,
                    drawStyle = Fill,
                    shadow = Shadow.None,
                )
            }
        }
    }
}

internal fun subtitleFileColor(color: Color, textAlpha: Float): Color =
    color.copy(alpha = color.alpha * textAlpha)

private fun AnnotatedString.mapColors(transform: (Color) -> Color): AnnotatedString = buildAnnotatedString {
    append(this@mapColors.text)
    this@mapColors.spanStyles.forEach { range ->
        val color = range.item.color
        addStyle(
            if (color.isSpecified) range.item.copy(color = transform(color)) else range.item,
            range.start,
            range.end,
        )
    }
}
