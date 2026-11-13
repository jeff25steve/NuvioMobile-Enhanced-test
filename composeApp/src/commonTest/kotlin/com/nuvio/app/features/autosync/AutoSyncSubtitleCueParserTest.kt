package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoSyncSubtitleCueParserTest {
    @Test
    fun parsesRepeatedTtmlCuesWithStableTimingAndNormalizedMarkup() {
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

        val cues = AutoSyncSubtitleCueParser.parse(ttml, "captions.ttml")

        assertEquals(96, cues.size)
        assertEquals(0L, cues.first().startTimeMs)
        assertEquals(1_000L, cues.first().endTimeMs)
        assertEquals("Cue 0\nSecond line", cues.first().text)
        assertEquals(95_000L, cues.last().startTimeMs)
        assertEquals(96_000L, cues.last().endTimeMs)
        assertEquals("Cue 95\nSecond line", cues.last().text)
    }
}
