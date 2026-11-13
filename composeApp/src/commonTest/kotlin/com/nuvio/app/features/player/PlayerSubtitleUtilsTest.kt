package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSubtitleUtilsTest {

    @Test
    fun testParseFromTextSrtWithEndTimes() {
        val srtText = """
            1
            00:00:01,000 --> 00:00:04,500
            Hello world!
            Second line

            2
            00:00:05,000 --> 00:00:08,000
            Testing sub
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(srtText, "sub.srt")
        assertEquals(2, cues.size)

        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4500L, cues[0].endTimeMs)
        assertEquals("Hello world!\nSecond line", cues[0].text)

        assertEquals(5000L, cues[1].startTimeMs)
        assertEquals(8000L, cues[1].endTimeMs)
        assertEquals("Testing sub", cues[1].text)
    }

    @Test
    fun testParseFromTextWebVttMetadataSkipping() {
        val vttText = """
            WEBVTT

            STYLE
            ::cue {
              color: yellow;
            }

            NOTE This is a comment

            00:01.000 --> 00:04.000
            VTT Cue Text
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(vttText, "sub.vtt")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4000L, cues[0].endTimeMs)
        assertEquals("VTT Cue Text", cues[0].text)
    }

    @Test
    fun testZeroDurationCueFiltering() {
        val srtText = """
            1
            00:00:01,000 --> 00:00:01,000
            Zero duration cue

            2
            00:00:02,000 --> 00:00:05,000
            Valid cue
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(srtText, "test.srt")
        assertEquals(1, cues.size)
        assertEquals("Valid cue", cues[0].text)
    }

    @Test
    fun testRepeatedTtmlCueParsingKeepsNormalizedTextAndTiming() {
        fun timestamp(totalSeconds: Int): String {
            val minutes = (totalSeconds / 60).toString().padStart(2, '0')
            val seconds = (totalSeconds % 60).toString().padStart(2, '0')
            return "00:$minutes:$seconds.000"
        }

        val ttml = buildString {
            append("<tt><body><div>")
            repeat(96) { index ->
                append(
                    """<p begin="${timestamp(index)}" end="${timestamp(index + 1)}"> Cue $index <br/>Second <span>line</span> </p>""",
                )
            }
            append("</div></body></tt>")
        }

        val cues = PlayerSubtitleCueParser.parse(ttml, "captions.ttml")

        assertEquals(96, cues.size)
        assertEquals(0L, cues.first().startTimeMs)
        assertEquals(1_000L, cues.first().endTimeMs)
        assertEquals("Cue 0\nSecond line", cues.first().text)
        assertEquals(95_000L, cues.last().startTimeMs)
        assertEquals(96_000L, cues.last().endTimeMs)
        assertEquals("Cue 95\nSecond line", cues.last().text)
    }

    @Test
    fun testParseMultilineTtmlCue() {
        val ttmlText = """
            <tt>
              <body>
                <div>
                  <p begin="00:00:01.000" end="00:00:04.500">
                    Hello<br/>world
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parse(ttmlText, "sub.ttml")

        assertEquals(1, cues.size)
        assertEquals(1000L, cues.single().startTimeMs)
        assertEquals(4500L, cues.single().endTimeMs)
        assertEquals("Hello\nworld", cues.single().text)
    }
}
