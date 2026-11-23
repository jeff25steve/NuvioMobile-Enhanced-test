package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleRenderingTest {
    @Test
    fun authoredAssStylingIsPreservedAtDefaultSize() {
        assertEquals("no", SubtitleRendering.assOverride("ass", customizeAss = false))
        assertEquals("no", SubtitleRendering.assOverride("ssa", customizeAss = false))
    }

    @Test
    fun changingFontSizeUsesScaleOnlyOverride() {
        assertEquals("scale", SubtitleRendering.assOverride("ass", customizeAss = false, assScale = 1.25))
        assertTrue(SubtitleStyleState.DEFAULT.assScale == 1.0)
    }

    @Test
    fun fullAssCustomizationRequiresExplicitOptIn() {
        assertEquals("force", SubtitleRendering.assOverride("ass", customizeAss = true))
        assertEquals("force", SubtitleRendering.assOverride("ssa", customizeAss = true, assScale = 1.4))
    }

    @Test
    fun nonAssTracksNeverReceiveAssOverrides() {
        assertEquals("no", SubtitleRendering.assOverride("subrip", customizeAss = true, assScale = 1.5))
        assertEquals("no", SubtitleRendering.assOverride("webvtt", customizeAss = false, assScale = 1.5))
    }

    @Test
    fun newCustomizationPreferenceDefaultsOffForExistingProfiles() {
        assertFalse(SubtitleAppearance().customizeAss)
    }
}
