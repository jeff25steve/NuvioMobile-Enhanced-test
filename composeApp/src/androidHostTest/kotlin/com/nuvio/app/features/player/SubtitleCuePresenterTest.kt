@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.media3.common.text.Cue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleCuePresenterTest {
    @Test
    fun liveStyleChangesRetainPausedCuesAndClearAtCueEnd() {
        var style = SubtitleStyleState.DEFAULT
        val presenter = SubtitleCuePresenter { style }
        val cues = listOf(Cue.Builder().setText("First caption").build())
        assertEquals(cues, presenter.present(cues))
        style = style.copy(appearance = SubtitleAppearance(fontId = "serif"))
        assertTrue(presenter.refresh().isEmpty())
        assertEquals("First caption", presenter.cues.bottomText.text)
        style = SubtitleStyleState.DEFAULT
        assertEquals(cues, presenter.refresh())
        assertTrue(presenter.cues.isEmpty)
        style = style.copy(appearance = SubtitleAppearance(shadowEnabled = true))
        presenter.refresh()
        presenter.present(emptyList())
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun bitmapCuesAndStyledTracksKeepTheirNativeOutput() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(fontId = "mono")) }
        val text = Cue.Builder().setText("Caption").build()
        val bitmap = Cue.Builder().setBitmap(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
            .setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.2f).setBitmapHeight(0.1f).build()
        assertEquals(listOf(bitmap), presenter.present(listOf(text, bitmap)))
        assertEquals("Caption", presenter.cues.bottomText.text)
        assertEquals(listOf(text), presenter.present(listOf(text), canStyle = false))
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun onlyUnpositionedSrtAndWebVttTextCuesUseLetterboxPlacement() {
        val presenter = SubtitleCuePresenter {
            SubtitleStyleState(
                appearance = SubtitleAppearance(placeUnpositionedTextInLetterbox = true),
            )
        }
        val defaultCue = Cue.Builder().setText("Plain SRT").build()
        val positionedX = Cue.Builder().setText("Positioned horizontally").setPosition(0.5f).build()
        val positionedY = Cue.Builder().setText("Positioned vertically")
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION).build()

        presenter.present(
            listOf(defaultCue, positionedX, positionedY),
            allowLetterboxPlacement = true,
        )

        assertEquals(
            listOf(true, false, false),
            presenter.cues.blocks.map { it.placeInLetterbox },
        )
    }

    @Test
    fun letterboxPlacementRequiresAnEnabledPreferenceAndSupportedTextMimeType() {
        assertTrue(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP))
        assertTrue(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.TEXT_VTT))
        assertTrue(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.APPLICATION_MP4VTT))
        assertFalse(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.TEXT_SSA))
        assertFalse(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.APPLICATION_PGS))
        assertFalse(supportsLetterboxTextSubtitleMimeType(androidx.media3.common.MimeTypes.APPLICATION_TTML))

        val presenter = SubtitleCuePresenter { SubtitleStyleState.DEFAULT }
        val cue = Cue.Builder().setText("Plain SRT").build()
        presenter.present(listOf(cue), allowLetterboxPlacement = true)
        assertFalse(presenter.cues.blocks.single().placeInLetterbox)
    }

    @Test
    fun letterboxOnlyPreferenceDoesNotForceOverlayForUnsupportedSubtitleMimeTypes() {
        val presenter = SubtitleCuePresenter {
            SubtitleStyleState(
                appearance = SubtitleAppearance(placeUnpositionedTextInLetterbox = true),
            )
        }
        val cue = Cue.Builder().setText("Plain text from another format").build()

        val nativeCues = presenter.present(listOf(cue), allowLetterboxPlacement = false)

        assertEquals(listOf(cue), nativeCues)
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun letterboxPlacementPreferenceIsReevaluatedWhenStyleChanges() {
        var style = SubtitleStyleState.DEFAULT
        val presenter = SubtitleCuePresenter { style }
        val cue = Cue.Builder().setText("Plain VTT").build()

        presenter.present(listOf(cue), allowLetterboxPlacement = true)
        assertFalse(presenter.cues.blocks.single().placeInLetterbox)

        style = style.copy(
            appearance = style.appearance.copy(placeUnpositionedTextInLetterbox = true),
        )
        presenter.refresh()
        assertTrue(presenter.cues.blocks.single().placeInLetterbox)
    }

    @Test
    fun embeddedBackgroundSpansSurviveLetterboxTextOverlay() {
        val presenter = SubtitleCuePresenter {
            SubtitleStyleState(
                appearance = SubtitleAppearance(placeUnpositionedTextInLetterbox = true),
            )
        }
        val highlighted = SpannableString("Highlighted").apply {
            setSpan(
                BackgroundColorSpan(android.graphics.Color.RED),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        presenter.present(
            listOf(Cue.Builder().setText(highlighted).build()),
            allowLetterboxPlacement = true,
        )

        assertEquals(Color.Red, presenter.cues.blocks.single().text.spanStyles.single().item.background)
    }

    @Test
    fun topPositionedCuesAndItalicSpansReachTheOverlay() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val italic = SpannableString("Italic line").apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val caption = Cue.Builder().setText(italic).build()
        val sign = Cue.Builder().setText("Sign").setLine(0.08f, Cue.LINE_TYPE_FRACTION).build()

        presenter.present(listOf(caption, sign))

        assertEquals("Sign", presenter.cues.topText.text)
        assertEquals("Italic line", presenter.cues.bottomText.text)
        assertEquals(FontStyle.Italic, presenter.cues.bottomText.spanStyles.single().item.fontStyle)
    }

    @Test
    fun cueColorsAndSrtAlignmentReachTheOverlay() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val colored = SpannableString("Yellow").apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.YELLOW), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val topLeft = Cue.Builder().setText(colored)
            .setLine(0.08f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0.08f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build()

        presenter.present(listOf(topLeft))

        val block = presenter.cues.blocks.single()
        assertEquals(SubtitleVerticalPosition.TOP, block.vertical)
        assertEquals(0.08f, block.positionX)
        assertEquals(0f, block.positionXAnchor)
        assertEquals(Color.Yellow, block.text.spanStyles.single().item.color)
    }

    @Test
    fun lineNumberCuesStayOnTheNativeRenderer() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val top = Cue.Builder().setText("Top row").setLine(0f, Cue.LINE_TYPE_NUMBER).build()
        val bottom = Cue.Builder().setText("Bottom row").setLine(-1f, Cue.LINE_TYPE_NUMBER).build()

        assertEquals(listOf(top, bottom), presenter.present(listOf(top, bottom)))
        assertTrue(presenter.cues.isEmpty)
    }

    @Test
    fun fractionalCueGeometryAndTextAlignmentArePreserved() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder()
            .setText("Positioned")
            .setPosition(0.2f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_END)
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.6f)
            .setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(0.2f, block.positionX)
        assertEquals(1f, block.positionXAnchor)
        assertEquals(0.8f, block.positionY)
        assertEquals(0.5f, block.positionYAnchor)
        assertEquals(0.2f, block.widthFraction)
        assertEquals(SubtitleHorizontalPosition.END, block.textAlignment)
        assertTrue(block.fillCueWidth)
    }

    @Test
    fun cuesWithoutExplicitSizeUseMeasuredTextWidth() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder().setText("Measured").setPosition(0.75f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(0.75f, block.positionX)
        assertEquals(0.5f, block.positionXAnchor)
        assertEquals(0.5f, block.widthFraction)
        assertFalse(block.fillCueWidth)
    }

    @Test
    fun textAlignmentDoesNotMoveTheDefaultBottomCenterCueBox() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder().setText("Aligned").setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE).build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(SubtitleHorizontalPosition.CENTER, block.horizontal)
        assertEquals(SubtitleHorizontalPosition.END, block.textAlignment)
        assertEquals(null, block.positionX)
    }

    @Test
    fun unsetCueAnchorsUseMedia3StartDefaults() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder()
            .setText("Default anchors")
            .setPosition(0.25f)
            .setLine(0.75f, Cue.LINE_TYPE_FRACTION)
            .build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(0f, block.positionXAnchor)
        assertEquals(0f, block.positionYAnchor)
    }

    @Test
    fun invalidCueFractionsAreIgnoredWithoutInvalidLayoutValues() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder()
            .setText("Invalid")
            .setPosition(Float.NaN)
            .setLine(Float.NaN, Cue.LINE_TYPE_FRACTION)
            .setSize(Float.NaN)
            .build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(null, block.positionX)
        assertEquals(null, block.positionY)
        assertEquals(null, block.widthFraction)
        assertFalse(block.fillCueWidth)
    }

    @Test
    fun embeddedCueWindowColorAndTextSizeArePreserved() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder()
            .setText("Styled cue")
            .setWindowColor(android.graphics.Color.RED)
            .setTextSize(0.04f, Cue.TEXT_SIZE_TYPE_FRACTIONAL)
            .build()

        presenter.present(listOf(cue))

        val block = presenter.cues.blocks.single()
        assertEquals(Color.Red, block.windowColor)
        assertEquals(0.04f, block.textSizeValue)
        assertEquals(SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT, block.textSizeType)
    }

    @Test
    fun absoluteAndIgnorePaddingCueTextSizesKeepTheirMedia3Modes() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val absolute = Cue.Builder()
            .setText("Absolute")
            .setTextSize(28f, Cue.TEXT_SIZE_TYPE_ABSOLUTE)
            .build()
        val ignorePadding = Cue.Builder()
            .setText("Fractional")
            .setTextSize(0.05f, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING)
            .build()

        presenter.present(listOf(absolute, ignorePadding))

        assertEquals(
            listOf(28f, 0.05f),
            presenter.cues.blocks.map { it.textSizeValue },
        )
        assertEquals(
            listOf(
                SubtitleCueTextSizeType.ABSOLUTE_PX,
                SubtitleCueTextSizeType.FRACTIONAL_VIEWPORT_IGNORE_PADDING,
            ),
            presenter.cues.blocks.map { it.textSizeType },
        )
    }

    @Test
    fun cuesWithoutEmbeddedWindowColorKeepUserBackgroundFallback() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        presenter.present(listOf(Cue.Builder().setText("Default").build()))

        assertEquals(null, presenter.cues.blocks.single().windowColor)
    }

    @Test
    fun verticalWritingCuesStayOnTheNativeRenderer() {
        val presenter = SubtitleCuePresenter { SubtitleStyleState(appearance = SubtitleAppearance(shadowEnabled = true)) }
        val cue = Cue.Builder().setText("縦書き").setVerticalType(Cue.VERTICAL_TYPE_RL).build()

        assertEquals(listOf(cue), presenter.present(listOf(cue)))
        assertTrue(presenter.cues.isEmpty)
    }
}
