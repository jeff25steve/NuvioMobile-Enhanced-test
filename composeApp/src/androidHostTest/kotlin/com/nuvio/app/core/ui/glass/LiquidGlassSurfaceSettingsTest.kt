package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color

class LiquidGlassSurfaceSettingsTest {
    @Test
    fun adaptiveGlassFilmAddsSheenOnBlackAndShadeOnWhite() {
        val blackFilm = liquidGlassAdaptiveFilm(Color.Black)
        val whiteFilm = liquidGlassAdaptiveFilm(Color.White)
        val translucentWhiteFilm = liquidGlassAdaptiveFilm(Color.White.copy(alpha = 0.08f))

        assertTrue(blackFilm.bodyTintAlpha > whiteFilm.bodyTintAlpha)
        assertTrue(blackFilm.topSheenAlpha > whiteFilm.topSheenAlpha)
        assertTrue(whiteFilm.bottomShadeAlpha > blackFilm.bottomShadeAlpha)
        assertTrue(translucentWhiteFilm.bodyTintAlpha > 0f)
        assertTrue(translucentWhiteFilm.bodyTintAlpha < blackFilm.bodyTintAlpha)
        assertTrue(translucentWhiteFilm.topSheenAlpha > 0f)
        assertTrue(blackFilm.bodyTintAlpha in 0f..0.08f)
        assertTrue(blackFilm.topSheenAlpha in 0f..0.14f)
        assertTrue(whiteFilm.bottomShadeAlpha in 0f..0.08f)
    }

    @Test
    fun existingGlassSurfacesRemainEnabledAndNewSurfacesKeepTheirCurrentAppearance() {
        assertTrue(LiquidGlassSurface.PLAYER.defaultEnabled)
        assertTrue(LiquidGlassSurface.STREAM_SELECTION.defaultEnabled)
        assertTrue(LiquidGlassSurface.DETAILS.defaultEnabled)
        assertTrue(LiquidGlassSurface.BOTTOM_SHEETS.defaultEnabled)

        assertFalse(LiquidGlassSurface.SEARCH.defaultEnabled)
        assertFalse(LiquidGlassSurface.LIBRARY.defaultEnabled)
        assertFalse(LiquidGlassSurface.SETTINGS.defaultEnabled)
    }

    @Test
    fun unsetOverridesInheritTheOriginalRecipeAndCanBeResetIndependently() {
        val initial = LiquidGlassSurfacesSettings()
        assertEquals(LiquidGlassSurfaceOverrides(), initial.forSurface(LiquidGlassSurface.PLAYER))
        assertEquals(LiquidGlassSurfaceOverrides(), initial.forSurface(LiquidGlassSurface.SEARCH))

        val customized = initial.withSurface(
            LiquidGlassSurface.SEARCH,
            LiquidGlassSurfaceOverrides(enabled = true, blurRadiusDp = 12f),
        )
        assertEquals(true, customized.forSurface(LiquidGlassSurface.SEARCH).enabled)
        assertEquals(12f, customized.forSurface(LiquidGlassSurface.SEARCH).blurRadiusDp)
        assertEquals(LiquidGlassSurfaceOverrides(), customized.forSurface(LiquidGlassSurface.PLAYER))

        assertEquals(initial, customized.withSurface(LiquidGlassSurface.SEARCH, LiquidGlassSurfaceOverrides()))
    }

    @Test
    fun detailAndStreamSurfacesCanBeCustomizedIndependently() {
        val initial = LiquidGlassSurfacesSettings()
        val customized = initial
            .withSurface(
                LiquidGlassSurface.DETAILS,
                LiquidGlassSurfaceOverrides(enabled = false, cornerRadiusPercent = 50f),
            )
            .withSurface(
                LiquidGlassSurface.DETAIL_HEADER,
                LiquidGlassSurfaceOverrides(enabled = true, blurRadiusDp = 10f),
            )
            .withSurface(
                LiquidGlassSurface.DETAIL_CHIPS,
                LiquidGlassSurfaceOverrides(enabled = false, cornerRadiusPercent = 24f),
            )
            .withSurface(
                LiquidGlassSurface.DETAIL_CONTENT_CARDS,
                LiquidGlassSurfaceOverrides(enabled = true, cornerRadiusPercent = 16f),
            )
            .withSurface(
                LiquidGlassSurface.DETAIL_PRIMARY_ACTION,
                LiquidGlassSurfaceOverrides(enabled = true, cornerRadiusPercent = 50f),
            )
            .withSurface(
                LiquidGlassSurface.STREAM_SELECTION,
                LiquidGlassSurfaceOverrides(enabled = false, blurRadiusDp = 4f),
            )
            .withSurface(
                LiquidGlassSurface.STREAM_RESULTS,
                LiquidGlassSurfaceOverrides(enabled = true, blurRadiusDp = 20f),
            )

        assertEquals(false, customized.forSurface(LiquidGlassSurface.DETAILS).enabled)
        assertEquals(10f, customized.forSurface(LiquidGlassSurface.DETAIL_HEADER).blurRadiusDp)
        assertEquals(24f, customized.forSurface(LiquidGlassSurface.DETAIL_CHIPS).cornerRadiusPercent)
        assertEquals(16f, customized.forSurface(LiquidGlassSurface.DETAIL_CONTENT_CARDS).cornerRadiusPercent)
        assertEquals(50f, customized.forSurface(LiquidGlassSurface.DETAIL_PRIMARY_ACTION).cornerRadiusPercent)
        assertEquals(4f, customized.forSurface(LiquidGlassSurface.STREAM_SELECTION).blurRadiusDp)
        assertEquals(20f, customized.forSurface(LiquidGlassSurface.STREAM_RESULTS).blurRadiusDp)
        assertEquals(LiquidGlassSurfaceOverrides(), initial.forSurface(LiquidGlassSurface.STREAM_RESULTS))
    }

    @Test
    fun providerFiltersHaveTheirOwnDefaultAndCanBeToggledWithoutChangingResults() {
        val initial = LiquidGlassSurfacesSettings()
        assertTrue(LiquidGlassSurface.STREAM_FILTERS.defaultEnabled)
        assertTrue(LiquidGlassSurface.STREAM_SELECTION.defaultEnabled)
        assertTrue(LiquidGlassSurface.STREAM_RESULTS.defaultEnabled)

        val updated = initial.withSurface(
            LiquidGlassSurface.STREAM_FILTERS,
            LiquidGlassSurfaceOverrides(enabled = false, blurRadiusDp = 4f),
        )

        assertEquals(false, updated.forSurface(LiquidGlassSurface.STREAM_FILTERS).enabled)
        assertEquals(4f, updated.forSurface(LiquidGlassSurface.STREAM_FILTERS).blurRadiusDp)
        assertEquals(LiquidGlassSurfaceOverrides(), updated.forSurface(LiquidGlassSurface.STREAM_SELECTION))
        assertEquals(LiquidGlassSurfaceOverrides(), updated.forSurface(LiquidGlassSurface.STREAM_RESULTS))
    }

    @Test
    fun interactiveLayoutGroupsContainOnlyTheirOwnGlassSurfaces() {
        assertEquals(
            listOf(
                LiquidGlassSurface.DETAIL_HEADER,
                LiquidGlassSurface.DETAILS,
                LiquidGlassSurface.DETAIL_PRIMARY_ACTION,
                LiquidGlassSurface.DETAIL_CHIPS,
                LiquidGlassSurface.DETAIL_CONTENT_CARDS,
            ),
            LiquidGlassSurfaceGroup.DETAIL_PAGE.surfaces,
        )
        assertEquals(
            listOf(
                LiquidGlassSurface.STREAM_FILTERS,
                LiquidGlassSurface.STREAM_SELECTION,
                LiquidGlassSurface.STREAM_RESULTS,
            ),
            LiquidGlassSurfaceGroup.STREAM_PICKER.surfaces,
        )
    }

    @Test
    fun detailPageGroupExposesPrimaryActionAndSecondaryActionsAsDistinctEditableSurfaces() {
        val group = LiquidGlassSurfaceGroup.DETAIL_PAGE

        assertTrue(LiquidGlassSurface.DETAIL_PRIMARY_ACTION in group.surfaces)
        assertTrue(LiquidGlassSurface.DETAILS in group.surfaces)
        assertFalse(group.surfaces.indexOf(LiquidGlassSurface.DETAIL_PRIMARY_ACTION) ==
            group.surfaces.indexOf(LiquidGlassSurface.DETAILS))
    }

    @Test
    fun linkedGlassEffectLeavesEachComponentShapeAndIconColorIndependent() {
        val local = LiquidGlassSurfaceOverrides(
            enabled = true,
            cornerRadiusPercent = 0f,
            defaultIconColorArgb = 0xFF123456.toInt(),
        )
        val shared = LiquidGlassSurfaceOverrides(
            enabled = false,
            blurRadiusDp = 20f,
            refractionHeightDp = 14f,
            refractionAmountDp = 24f,
            tintOpacity = 0.32f,
            withVibrancy = false,
            withHighlight = false,
            withShadow = true,
            chromaticAberration = true,
            depthEffect = true,
        )

        val linked = local.withGlassEffectFrom(shared)

        assertEquals(false, linked.enabled)
        assertEquals(20f, linked.blurRadiusDp)
        assertEquals(14f, linked.refractionHeightDp)
        assertEquals(24f, linked.refractionAmountDp)
        assertEquals(0.32f, linked.tintOpacity)
        assertEquals(false, linked.withVibrancy)
        assertEquals(false, linked.withHighlight)
        assertEquals(true, linked.withShadow)
        assertEquals(true, linked.chromaticAberration)
        assertEquals(true, linked.depthEffect)
        assertEquals(0f, linked.cornerRadiusPercent)
        assertEquals(0xFF123456.toInt(), linked.defaultIconColorArgb)
    }

    @Test
    fun olderSettingsMigrateDetailAndStreamOverridesToNewlySplitSurfaces() {
        val legacyJson = """
            {
              "details": {"enabled": false, "blurRadiusDp": 11.0, "cornerRadiusPercent": 32.0},
              "streamSelection": {"enabled": false, "blurRadiusDp": 7.0}
            }
        """.trimIndent()

        val restored = decodeLiquidGlassSurfaceSettings(legacyJson)

        assertEquals(restored.details, restored.detailHeader)
        assertEquals(restored.details, restored.detailChips)
        assertEquals(restored.details, restored.detailContentCards)
        assertEquals(restored.details, restored.detailPrimaryAction)
        assertEquals(restored.streamSelection, restored.streamResults)
    }

    @Test
    fun sanitizingClampsNumericValuesWithoutInventingOverrides() {
        val sanitized = LiquidGlassSurfaceOverrides(
            cornerRadiusPercent = 80f,
            blurRadiusDp = -4f,
            refractionHeightDp = 200f,
            refractionAmountDp = Float.NaN,
            tintOpacity = 2f,
            defaultIconColorArgb = 0x006699CC,
        ).sanitized()

        assertEquals(50f, sanitized.cornerRadiusPercent)
        assertEquals(0f, sanitized.blurRadiusDp)
        assertEquals(48f, sanitized.refractionHeightDp)
        assertNull(sanitized.refractionAmountDp)
        assertEquals(1f, sanitized.tintOpacity)
        assertEquals(0xFF6699CC.toInt(), sanitized.defaultIconColorArgb)

        assertEquals(null, LiquidGlassSurfaceOverrides().sanitized().blurRadiusDp)
    }

    @Test
    fun surfacePreferencesRoundTripAndMalformedStorageFallsBackSafely() {
        val settings = LiquidGlassSurfacesSettings().withSurface(
            LiquidGlassSurface.PLAYER,
            LiquidGlassSurfaceOverrides(enabled = false, blurRadiusDp = 7.5f),
        ).withSurface(
            LiquidGlassSurface.LIBRARY,
            LiquidGlassSurfaceOverrides(enabled = true, defaultIconColorArgb = 0xFF336699.toInt()),
        ).withSurface(
            LiquidGlassSurface.BOTTOM_SHEETS,
            LiquidGlassSurfaceOverrides(
                enabled = false,
                cornerRadiusPercent = 22f,
                withVibrancy = false,
                withHighlight = false,
                withShadow = true,
            ),
        )

        val encoded = encodeLiquidGlassSurfaceSettings(settings)
        assertEquals(settings, decodeLiquidGlassSurfaceSettings(encoded))
        assertEquals(LiquidGlassSurfacesSettings(), decodeLiquidGlassSurfaceSettings("{broken-json"))
        assertEquals(LiquidGlassSurfacesSettings(), decodeLiquidGlassSurfaceSettings(null))
    }
}
