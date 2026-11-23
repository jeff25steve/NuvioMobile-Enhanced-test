@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue

internal class SubtitleCuePresenter(private val style: () -> SubtitleStyleState) {
    var cues by mutableStateOf(StyledSubtitleCues())
        private set
    private var lastCues = emptyList<Cue>()
    private var canStyle = true
    private var allowLetterboxPlacement = false

    fun present(
        cues: List<Cue>,
        canStyle: Boolean = this.canStyle,
        allowLetterboxPlacement: Boolean = this.allowLetterboxPlacement,
    ): List<Cue> {
        this.lastCues = cues
        this.canStyle = canStyle
        this.allowLetterboxPlacement = allowLetterboxPlacement
        return refresh()
    }

    fun refresh(): List<Cue> {
        val currentStyle = style()
        if (!canStyle || !currentStyle.usesStyledOverlay) {
            cues = StyledSubtitleCues()
            return lastCues
        }

        val styled = lastCues.filter { !it.text.isNullOrBlank() && !it.requiresNativeSubtitleOutput() }
        val native = lastCues.filter { it.text.isNullOrBlank() || it.requiresNativeSubtitleOutput() }
        cues = styledSubtitleCues(styled.map { cue ->
            val textAlignment = cue.subtitleTextAlignment()
            val positionX = cue.position.takeIf(::isSubtitleCueFraction)
            val positionXAnchor = cue.positionAnchor.toSubtitleAnchorFraction()
            val positionY = cue.line.takeIf {
                cue.lineType == Cue.LINE_TYPE_FRACTION && isSubtitleCueFraction(it)
            }
            val positionYAnchor = cue.lineAnchor.toSubtitleAnchorFraction()
            val cueSize = cue.size.takeIf(::isSubtitleCueFraction)
            val widthFraction = if (cueSize != null || positionX != null) {
                subtitleCueWidthFraction(
                    positionFraction = positionX,
                    anchorFraction = positionXAnchor,
                    requestedSize = cueSize ?: 1f,
                )
            } else {
                null
            }
            val cueTextSizeType = cue.subtitleTextSizeType()
            val cueTextSizeValue = cue.textSize.takeIf {
                cueTextSizeType != null && it.isFinite() && it > 0f
            }
            StyledSubtitleBlock(
                text = cue.text?.toSubtitleAnnotatedString() ?: AnnotatedString(""),
                vertical = cue.verticalPosition(),
                // Cue.position is handled by its exact fraction below. Unset positions are centred.
                horizontal = SubtitleHorizontalPosition.CENTER,
                positionX = positionX,
                positionXAnchor = positionXAnchor,
                positionY = positionY,
                positionYAnchor = positionYAnchor,
                widthFraction = widthFraction,
                fillCueWidth = cueSize != null,
                textAlignment = textAlignment,
                windowColor = cue.windowColor.takeIf { cue.windowColorSet }?.let { Color(it) },
                textSizeValue = cueTextSizeValue,
                textSizeType = cueTextSizeType,
                placeInLetterbox = allowLetterboxPlacement &&
                    currentStyle.appearance.placeUnpositionedTextInLetterbox &&
                    cue.bitmap == null &&
                    cue.line == Cue.DIMEN_UNSET &&
                    cue.position == Cue.DIMEN_UNSET &&
                    cue.verticalType == Cue.TYPE_UNSET,
            )
        })
        return native
    }
}

internal fun supportsLetterboxTextSubtitleMimeType(mimeType: String?): Boolean =
    mimeType == MimeTypes.APPLICATION_SUBRIP ||
        mimeType == MimeTypes.TEXT_VTT ||
        mimeType == MimeTypes.APPLICATION_MP4VTT

private fun Cue.subtitleTextSizeType(): SubtitleCueTextSizeType? = when (textSizeType) {
    Cue.TEXT_SIZE_TYPE_ABSOLUTE -> SubtitleCueTextSizeType.ABSOLUTE_PX
    Cue.TEXT_SIZE_TYPE_FRACTIONAL -> SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT
    Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING ->
        SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT_IGNORE_PADDING
    else -> null
}

private fun Cue.requiresNativeSubtitleOutput(): Boolean =
    bitmap != null ||
        verticalType != Cue.TYPE_UNSET ||
        (line != Cue.DIMEN_UNSET && lineType != Cue.LINE_TYPE_FRACTION)

private fun isSubtitleCueFraction(value: Float): Boolean =
    value.isFinite() && value in 0f..1f

private fun Int.toSubtitleAnchorFraction(): Float = when (this) {
    Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
    Cue.ANCHOR_TYPE_END -> 1f
    // Media3's SubtitlePainter treats TYPE_UNSET like START for horizontal and fractional line anchors.
    else -> 0f
}

private fun Cue.subtitleTextAlignment(): SubtitleHorizontalPosition = when (textAlignment) {
    Layout.Alignment.ALIGN_NORMAL -> SubtitleHorizontalPosition.START
    Layout.Alignment.ALIGN_OPPOSITE -> SubtitleHorizontalPosition.END
    else -> SubtitleHorizontalPosition.CENTER
}

private fun Cue.verticalPosition(): SubtitleVerticalPosition = when {
    line == Cue.DIMEN_UNSET -> SubtitleVerticalPosition.BOTTOM
    lineType == Cue.LINE_TYPE_NUMBER ->
        if (line >= 0f) SubtitleVerticalPosition.TOP else SubtitleVerticalPosition.BOTTOM
    lineType != Cue.LINE_TYPE_FRACTION || !isSubtitleCueFraction(line) -> SubtitleVerticalPosition.BOTTOM
    line < 1f / 3f -> SubtitleVerticalPosition.TOP
    line <= 2f / 3f -> SubtitleVerticalPosition.MIDDLE
    else -> SubtitleVerticalPosition.BOTTOM
}

private fun CharSequence.toSubtitleAnnotatedString(): AnnotatedString {
    val spanned = this as? Spanned ?: return AnnotatedString(toString())
    return buildAnnotatedString {
        append(spanned.toString())
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val spanStyle = when {
                span is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                span is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
                span is BackgroundColorSpan -> SpanStyle(background = Color(span.backgroundColor))
                span is StyleSpan && span.style == Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                span is StyleSpan && span.style == Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                span is StyleSpan && span.style == Typeface.BOLD_ITALIC ->
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> null
            } ?: return@forEach
            addStyle(spanStyle, spanned.getSpanStart(span), spanned.getSpanEnd(span))
        }
    }
}
