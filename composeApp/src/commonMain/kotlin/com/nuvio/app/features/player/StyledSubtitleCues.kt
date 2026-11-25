package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt

internal enum class SubtitleVerticalPosition { TOP, MIDDLE, BOTTOM }

internal enum class SubtitleHorizontalPosition { START, CENTER, END }

internal enum class SubtitleCueTextSizeType {
    ABSOLUTE_PX,
    FRACTIONAL_VIEWPORT,
    FRACTIONAL_VIEWPORT_IGNORE_PADDING,
}

internal data class StyledSubtitleBlock(
    val text: AnnotatedString,
    val vertical: SubtitleVerticalPosition = SubtitleVerticalPosition.BOTTOM,
    val horizontal: SubtitleHorizontalPosition = SubtitleHorizontalPosition.CENTER,
    val positionX: Float? = null,
    val positionXAnchor: Float = 0f,
    val positionY: Float? = null,
    val positionYAnchor: Float = 0f,
    val widthFraction: Float? = null,
    val fillCueWidth: Boolean = true,
    val textAlignment: SubtitleHorizontalPosition = horizontal,
    val windowColor: Color? = null,
    val textSizeValue: Float? = null,
    val textSizeType: SubtitleCueTextSizeType? = null,
    val placeInLetterbox: Boolean = false,
)

internal data class StyledSubtitleCues(val blocks: List<StyledSubtitleBlock> = emptyList()) {
    val isEmpty: Boolean get() = blocks.all { it.text.isEmpty() }
}

private data class StyledSubtitleCueLayoutKey(
    val vertical: SubtitleVerticalPosition,
    val horizontal: SubtitleHorizontalPosition,
    val positionX: Float?,
    val positionXAnchor: Float,
    val positionY: Float?,
    val positionYAnchor: Float,
    val widthFraction: Float?,
    val fillCueWidth: Boolean,
    val textAlignment: SubtitleHorizontalPosition,
    val windowColor: Color?,
    val textSizeValue: Float?,
    val textSizeType: SubtitleCueTextSizeType?,
    val placeInLetterbox: Boolean,
)

internal fun styledSubtitleCues(blocks: List<StyledSubtitleBlock>): StyledSubtitleCues =
    StyledSubtitleCues(
        blocks
            .filter { it.text.isNotEmpty() }
            .groupBy { block ->
                StyledSubtitleCueLayoutKey(
                    vertical = block.vertical,
                    horizontal = block.horizontal,
                    positionX = block.positionX,
                    positionXAnchor = block.positionXAnchor,
                    positionY = block.positionY,
                    positionYAnchor = block.positionYAnchor,
                    widthFraction = block.widthFraction,
                    fillCueWidth = block.fillCueWidth,
                    textAlignment = block.textAlignment,
                    windowColor = block.windowColor,
                    textSizeValue = block.textSizeValue,
                    textSizeType = block.textSizeType,
                    placeInLetterbox = block.placeInLetterbox,
                )
            }
            .map { (key, grouped) ->
                StyledSubtitleBlock(
                    text = grouped.map { it.text }.joinLines(),
                    vertical = key.vertical,
                    horizontal = key.horizontal,
                    positionX = key.positionX,
                    positionXAnchor = key.positionXAnchor,
                    positionY = key.positionY,
                    positionYAnchor = key.positionYAnchor,
                    widthFraction = key.widthFraction,
                    fillCueWidth = key.fillCueWidth,
                    textAlignment = key.textAlignment,
                    windowColor = key.windowColor,
                    textSizeValue = key.textSizeValue,
                    textSizeType = key.textSizeType,
                    placeInLetterbox = key.placeInLetterbox,
                )
            },
    )

/**
 * Returns a display-relative bottom position when the complete cue fits on screen and, if a
 * bottom letterbox exists, fits entirely inside it. If the bar is too short, the caller keeps
 * the cue inside the video frame instead.
 */
internal fun subtitleBottomDisplayTopPx(
    viewportHeightPx: Int,
    videoBottomPx: Int,
    contentHeightPx: Int,
    bottomInsetPx: Int,
): Int? {
    if (
        viewportHeightPx <= 0 ||
        videoBottomPx < 0 ||
        contentHeightPx <= 0 ||
        bottomInsetPx < 0
    ) return null

    val top = viewportHeightPx.toLong() - contentHeightPx.toLong() - bottomInsetPx.toLong()
    val bottom = top + contentHeightPx.toLong()
    if (
        top < 0L ||
        bottom > viewportHeightPx.toLong() ||
        (videoBottomPx < viewportHeightPx && top < videoBottomPx.toLong())
    ) return null
    return top.toInt()
}

internal fun subtitleCueAxisOffset(
    viewportSize: Int,
    contentSize: Int,
    positionFraction: Float,
    anchorFraction: Float,
): Int {
    if (viewportSize <= 0 || contentSize <= 0) return 0
    val position = positionFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val anchor = anchorFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val maxOffset = (viewportSize - contentSize).coerceAtLeast(0)
    return (position * viewportSize - anchor * contentSize)
        .roundToInt()
        .coerceIn(0, maxOffset)
}

/**
 * Bounds an explicitly sized cue box so its requested anchor remains inside the viewport.
 * A null return means the source did not provide a usable size and the text should be measured
 * to its content width instead of stretching to a full cue box.
 */
internal fun subtitleCueWidthFraction(
    positionFraction: Float?,
    anchorFraction: Float,
    requestedSize: Float?,
): Float? {
    val requested = requestedSize?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
    val position = positionFraction?.takeIf { it.isFinite() && it in 0f..1f } ?: return requested
    val anchor = anchorFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val maxFromStart = if (anchor > 0f) position / anchor else 1f
    val maxFromEnd = if (anchor < 1f) (1f - position) / (1f - anchor) else 1f
    return minOf(requested, maxFromStart, maxFromEnd).coerceIn(0f, 1f)
}

internal fun subtitleCueTextSizePx(
    value: Float?,
    type: SubtitleCueTextSizeType?,
    rawViewHeight: Float,
    viewHeightMinusPadding: Float,
): Float? {
    if (value == null || !value.isFinite() || value <= 0f) return null
    val resolved = when (type) {
        SubtitleCueTextSizeType.ABSOLUTE_PX -> value
        SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT -> value * viewHeightMinusPadding
        SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT_IGNORE_PADDING -> value * rawViewHeight
        null -> return null
    }
    return resolved.takeIf { it.isFinite() && it > 0f }
}

internal fun List<AnnotatedString>.joinLines(): AnnotatedString = buildAnnotatedString {
    this@joinLines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        append(line)
    }
}

private val assOverrideBlock = Regex("""\{[^}]*\}""")
private val assAlignment = Regex("""\\an([1-9])(?!\d)""")
private val assStyleTag = Regex("""\\(?:([ibu])(\d*)(?![a-z])|(1?c)(?:&H([0-9A-Fa-f]{1,8})&?|(?![a-z]))|r(?![a-z]))""")

internal fun parseMpvSubtitleText(text: String): StyledSubtitleCues =
    styledSubtitleCues(
        text.split('\n').map(String::trim).filter { it.isNotEmpty() }.map { event ->
            val alignment = assOverrideBlock.findAll(event)
                .flatMap { block -> assAlignment.findAll(block.value) }
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: 2
            val horizontal = when (alignment % 3) {
                1 -> SubtitleHorizontalPosition.START
                0 -> SubtitleHorizontalPosition.END
                else -> SubtitleHorizontalPosition.CENTER
            }
            StyledSubtitleBlock(
                text = event.toAnnotatedEvent(),
                vertical = when (alignment) {
                    in 7..9 -> SubtitleVerticalPosition.TOP
                    in 4..6 -> SubtitleVerticalPosition.MIDDLE
                    else -> SubtitleVerticalPosition.BOTTOM
                },
                horizontal = horizontal,
                textAlignment = horizontal,
            )
        },
    )

private fun String.toAnnotatedEvent(): AnnotatedString = buildAnnotatedString {
    var italic = false
    var bold = false
    var underline = false
    var color: Color? = null
    var cursor = 0

    fun appendPlain(raw: String) {
        val plain = raw.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00A0")
        if (plain.isEmpty()) return
        if (!italic && !bold && !underline && color == null) {
            append(plain)
            return
        }
        withStyle(
            SpanStyle(
                color = color ?: Color.Unspecified,
                fontStyle = if (italic) FontStyle.Italic else null,
                fontWeight = if (bold) FontWeight.Bold else null,
                textDecoration = if (underline) TextDecoration.Underline else null,
            ),
        ) { append(plain) }
    }

    assOverrideBlock.findAll(this@toAnnotatedEvent).forEach { block ->
        appendPlain(substring(cursor, block.range.first))
        assStyleTag.findAll(block.value).forEach { tag ->
            val (style, value, colorTag, bgr) = tag.destructured
            when {
                style.isNotEmpty() -> {
                    val enabled = value != "0"
                    when (style) {
                        "i" -> italic = enabled
                        "b" -> bold = enabled
                        "u" -> underline = enabled
                    }
                }
                colorTag.isNotEmpty() -> color = bgr.takeIf { it.isNotEmpty() }?.let(::assBgrColor)
                else -> {
                    italic = false
                    bold = false
                    underline = false
                    color = null
                }
            }
        }
        cursor = block.range.last + 1
    }
    appendPlain(substring(cursor))
}

private fun assBgrColor(hex: String): Color {
    val bgr = hex.takeLast(6).padStart(6, '0').toInt(16)
    return Color(red = bgr and 0xFF, green = (bgr shr 8) and 0xFF, blue = (bgr shr 16) and 0xFF)
}
