package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyledSubtitleCuesTest {

    @Test
    fun italicTagsBecomeItalicText() {
        val cues = parseMpvSubtitleText("""{\i1}Italic line{\i0}\NPlain line""")

        assertEquals("Italic line\nPlain line", cues.bottomText.text)
        val italic = cues.bottomText.spanStyles.single()
        assertEquals(FontStyle.Italic, italic.item.fontStyle)
        assertEquals(0, italic.start)
        assertEquals("Italic line".length, italic.end)
    }

    @Test
    fun topAlignedEventsRenderAtTheTop() {
        val cues = parseMpvSubtitleText("Dialogue\n{\\an8}Sign")

        assertEquals("Sign", cues.topText.text)
        assertEquals("Dialogue", cues.bottomText.text)
    }

    @Test
    fun otherOverrideTagsAreDroppedWithoutChangingTheStyle() {
        val cues = parseMpvSubtitleText("""{\bord2\blur3\an2}Plain {\b1}bold{\b0}""")

        assertEquals("Plain bold", cues.bottomText.text)
        assertTrue(cues.topText.isEmpty())
        assertEquals(FontWeight.Bold, cues.bottomText.spanStyles.single().item.fontWeight)
    }

    @Test
    fun plainTextAndBlankInputAreHandled() {
        assertEquals("Hello\nthere", parseMpvSubtitleText(" Hello\nthere ").bottomText.text)
        assertTrue(parseMpvSubtitleText("").isEmpty)
    }

    @Test
    fun alignmentTagsPlaceEventsInAllNinePositions() {
        val cues = parseMpvSubtitleText("{\\an9}Top right\n{\\an4}Middle left\nDialogue")

        assertEquals(
            listOf(
                Triple("Top right", SubtitleVerticalPosition.TOP, SubtitleHorizontalPosition.END),
                Triple("Middle left", SubtitleVerticalPosition.MIDDLE, SubtitleHorizontalPosition.START),
                Triple("Dialogue", SubtitleVerticalPosition.BOTTOM, SubtitleHorizontalPosition.CENTER),
            ),
            cues.blocks.map { Triple(it.text.text, it.vertical, it.horizontal) },
        )
    }

    @Test
    fun colorTagsUseTheAssBlueGreenRedOrderAndResetClearsThem() {
        val cues = parseMpvSubtitleText("""{\c&H0000FF&}Red{\r} plain {\1c&H00FF00&}green""")

        assertEquals("Red plain green", cues.bottomText.text)
        assertEquals(listOf(Color.Red, Color.Green), cues.bottomText.spanStyles.map { it.item.color })
    }

    @Test
    fun anEmptyColorTagResetsToTheDefaultColor() {
        val cues = parseMpvSubtitleText("""{\c&H00FFFF&}John:{\c} where are you?""")

        assertEquals("John: where are you?", cues.bottomText.text)
        val yellow = cues.bottomText.spanStyles.single()
        assertEquals(Color.Yellow, yellow.item.color)
        assertEquals("John:".length, yellow.end)
    }

    @Test
    fun colorTagsWithoutTheClosingAmpersandStillApply() {
        assertEquals(Color.Red, parseMpvSubtitleText("""{\1c&H0000FF}Red""").bottomText.spanStyles.single().item.color)
    }

    @Test
    fun cueAxisPlacementRespectsStartMiddleAndEndAnchors() {
        assertEquals(200, subtitleCueAxisOffset(1_000, 200, 0.2f, 0f))
        assertEquals(100, subtitleCueAxisOffset(1_000, 200, 0.2f, 0.5f))
        assertEquals(0, subtitleCueAxisOffset(1_000, 200, 0.2f, 1f))
    }

    @Test
    fun cueAxisPlacementClampsToTheViewport() {
        assertEquals(0, subtitleCueAxisOffset(1_000, 300, 0.1f, 1f))
        assertEquals(700, subtitleCueAxisOffset(1_000, 300, 0.9f, 0f))
    }

    @Test
    fun bottomDisplayPlacementUsesLetterboxOnlyWhenTheWholeCueFits() {
        assertEquals(900, subtitleBottomDisplayTopPx(1_080, 850, 80, 100))
        assertEquals(null, subtitleBottomDisplayTopPx(1_080, 950, 80, 60))
        assertEquals(960, subtitleBottomDisplayTopPx(1_080, 1_080, 80, 40))
        assertEquals(null, subtitleBottomDisplayTopPx(1_080, 850, 0, 100))
        assertEquals(null, subtitleBottomDisplayTopPx(1_080, -1, 80, 100))
        assertEquals(null, subtitleBottomDisplayTopPx(1_080, 850, 80, -1))
        assertEquals(null, subtitleBottomDisplayTopPx(1_080, 1_200, 80, 2_000))
        assertEquals(1_000, subtitleBottomDisplayTopPx(1_080, 1_080, 80, 0))
        assertEquals(1_000, subtitleBottomDisplayTopPx(1_080, 1_200, 80, 0))
    }

    @Test
    fun letterboxEligibleAndVideoAnchoredCuesAreNotMerged() {
        val cues = styledSubtitleCues(
            listOf(
                StyledSubtitleBlock(AnnotatedString("Video anchored")),
                StyledSubtitleBlock(AnnotatedString("Display anchored"), placeInLetterbox = true),
            ),
        )

        assertEquals(2, cues.blocks.size)
        assertEquals(listOf(false, true), cues.blocks.map { it.placeInLetterbox })
    }

    @Test
    fun cueWidthShrinksToFitThePositionedAnchor() {
        assertEquals(0.2f, subtitleCueWidthFraction(0.2f, 1f, 0.8f), 0.0001f)
        assertEquals(0.4f, subtitleCueWidthFraction(0.2f, 0.5f, 0.8f))
        assertEquals(0.2f, subtitleCueWidthFraction(0.8f, 0f, 0.8f))
        assertEquals(0.5f, subtitleCueWidthFraction(null, 0.5f, 0.5f))
        assertEquals(0.5f, subtitleCueWidthFraction(0.75f, 0.5f, 1f))
        assertEquals(null, subtitleCueWidthFraction(null, 0.5f, null))
    }

    @Test
    fun cuesWithDifferentFractionalPlacementAreNotMerged() {
        val cues = styledSubtitleCues(
            listOf(
                StyledSubtitleBlock(AnnotatedString("Left"), positionX = 0.1f),
                StyledSubtitleBlock(AnnotatedString("Right"), positionX = 0.9f),
            ),
        )

        assertEquals(2, cues.blocks.size)
        assertEquals(listOf(0.1f, 0.9f), cues.blocks.map { it.positionX })
    }

    @Test
    fun cueTextSizeResolvesAbsoluteAndFractionalMedia3Units() {
        assertEquals(
            24f,
            subtitleCueTextSizePx(24f, SubtitleCueTextSizeType.ABSOLUTE_PX, 1_000f, 800f),
        )
        assertEquals(
            40f,
            subtitleCueTextSizePx(0.05f, SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT, 1_000f, 800f),
        )
        assertEquals(
            50f,
            subtitleCueTextSizePx(
                0.05f,
                SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT_IGNORE_PADDING,
                1_000f,
                800f,
            ),
        )
        assertEquals(null, subtitleCueTextSizePx(null, null, 1_000f, 800f))
    }

    @Test
    fun cuesWithDifferentWindowColorsAreNotMerged() {
        val cues = styledSubtitleCues(
            listOf(
                StyledSubtitleBlock(AnnotatedString("Red"), windowColor = Color.Red),
                StyledSubtitleBlock(AnnotatedString("Blue"), windowColor = Color.Blue),
            ),
        )

        assertEquals(2, cues.blocks.size)
        assertEquals(listOf(Color.Red, Color.Blue), cues.blocks.map { it.windowColor })
    }

    @Test
    fun fileColorsFollowTheTextOpacity() {
        assertEquals(Color.Yellow.copy(alpha = 0.5f), subtitleFileColor(Color.Yellow, textAlpha = 0.5f))
    }
}
