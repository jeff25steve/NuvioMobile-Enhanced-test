package com.nuvio.app.core.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color

class LiquidGlassSurfaceSettingsTest {
    @Test
    fun cornerRadiusUsesTheSamePercentRangeAsNavigationGlass() {
        assertEquals(0, effectiveLiquidGlassCornerRadiusPercent(0f))
        assertEquals(15, effectiveLiquidGlassCornerRadiusPercent(15f))
        assertEquals(36, effectiveLiquidGlassCornerRadiusPercent(36.2f))
        assertEquals(50, effectiveLiquidGlassCornerRadiusPercent(50f))
        assertEquals(50, effectiveLiquidGlassCornerRadiusPercent(Float.NaN))
        assertEquals(50, effectiveLiquidGlassCornerRadiusPercent(Float.POSITIVE_INFINITY))
        assertEquals(0, effectiveLiquidGlassCornerRadiusPercent(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun lensParametersStayInsideCornerRadiusAndComponentBounds() {
        val params = constrainLiquidGlassLensParameters(
            refractionHeightPx = 32f,
            refractionAmountPx = 120f,
            minimumCornerRadiusPx = 7.5f,
            minimumDimensionPx = 80f,
        )
        assertEquals(7.5f, params.refractionHeightPx)
        assertEquals(80f, params.refractionAmountPx)

        val squareShape = constrainLiquidGlassLensParameters(
            refractionHeightPx = 12f,
            refractionAmountPx = 18f,
            minimumCornerRadiusPx = 0f,
            minimumDimensionPx = 80f,
        )
        assertEquals(0f, squareShape.refractionHeightPx)
        assertEquals(18f, squareShape.refractionAmountPx)

        val unsafe = constrainLiquidGlassLensParameters(
            refractionHeightPx = Float.NaN,
            refractionAmountPx = Float.POSITIVE_INFINITY,
            minimumCornerRadiusPx = Float.NaN,
            minimumDimensionPx = Float.POSITIVE_INFINITY,
        )
        assertEquals(0f, unsafe.refractionHeightPx)
        assertEquals(0f, unsafe.refractionAmountPx)
    }

    @Test
    fun surfaceControlsMatchActualCapabilities() {
        val tintableSurfaces = setOf(
            LiquidGlassSurface.PLAYER,
            LiquidGlassSurface.DETAILS,
            LiquidGlassSurface.DETAIL_HEADER,
            LiquidGlassSurface.DETAIL_PRIMARY_ACTION,
            LiquidGlassSurface.SEARCH,
            LiquidGlassSurface.LIBRARY,
            LiquidGlassSurface.SETTINGS,
        )
        LiquidGlassSurface.entries.forEach { surface ->
            assertEquals(surface in tintableSurfaces, surface.supportsIconTint, surface.name)
        }
        assertFalse(LiquidGlassSurface.DETAIL_HEADER.supportsLens)
        assertFalse(LiquidGlassSurface.DETAIL_HEADER.defaultWithHighlight)
        assertTrue(LiquidGlassSurface.DETAILS.defaultWithHighlight)
    }

    @Test
    fun clearMaterialFallsBackWhenLensCannotActuallyRun() {
        assertEquals(
            LiquidGlassMaterial.LIQUID,
            effectiveLiquidGlassMaterial(
                LiquidGlassSurface.DETAIL_HEADER,
                LiquidGlassMaterial.CLEAR,
                refractionSupported = true,
                cornerRadiusPercent = 50f,
            ),
        )
        assertEquals(
            LiquidGlassMaterial.LIQUID,
            effectiveLiquidGlassMaterial(
                LiquidGlassSurface.SEARCH,
                LiquidGlassMaterial.CLEAR,
                refractionSupported = false,
                cornerRadiusPercent = 36f,
            ),
        )
        assertEquals(
            LiquidGlassMaterial.LIQUID,
            effectiveLiquidGlassMaterial(
                LiquidGlassSurface.SEARCH,
                LiquidGlassMaterial.CLEAR,
                refractionSupported = true,
                cornerRadiusPercent = 0f,
            ),
        )
        assertEquals(
            LiquidGlassMaterial.CLEAR,
            effectiveLiquidGlassMaterial(
                LiquidGlassSurface.SEARCH,
                LiquidGlassMaterial.CLEAR,
                refractionSupported = true,
                cornerRadiusPercent = 36f,
            ),
        )
    }

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
    fun previewRespectsSurfaceToggleButIgnoresTheGlobalMasterSwitch() {
        val surface = LiquidGlassSurface.DETAILS

        assertTrue(shouldRenderLiquidGlassSurface(surface, LiquidGlassSurfaceOverrides(), preview = true, masterEnabled = false))
        assertFalse(shouldRenderLiquidGlassSurface(surface, LiquidGlassSurfaceOverrides(enabled = false), preview = true, masterEnabled = true))
        assertTrue(shouldRenderLiquidGlassSurface(surface, LiquidGlassSurfaceOverrides(enabled = true), preview = false, masterEnabled = true))
        assertFalse(shouldRenderLiquidGlassSurface(surface, LiquidGlassSurfaceOverrides(enabled = true), preview = false, masterEnabled = false))
        assertFalse(shouldRenderLiquidGlassSurface(surface, LiquidGlassSurfaceOverrides(enabled = false), preview = false, masterEnabled = true))
        assertFalse(
            shouldRenderLiquidGlassSurface(
                surface,
                LiquidGlassSurfaceOverrides(enabled = true),
                preview = false,
                masterEnabled = true,
                platformSupported = false,
            ),
        )
        assertTrue(
            shouldRenderLiquidGlassSurface(
                surface,
                LiquidGlassSurfaceOverrides(enabled = true),
                preview = true,
                masterEnabled = false,
                platformSupported = false,
            ),
        )

        val settingsSurface = LiquidGlassSurface.SETTINGS
        assertFalse(
            shouldRenderLiquidGlassSurface(
                settingsSurface,
                LiquidGlassSurfaceOverrides(),
                preview = true,
                masterEnabled = false,
                platformSupported = false,
            ),
        )
        assertTrue(
            shouldRenderLiquidGlassSurface(
                settingsSurface,
                LiquidGlassSurfaceOverrides(enabled = true),
                preview = true,
                masterEnabled = false,
                platformSupported = false,
            ),
        )
    }

    @Test
    fun playerGlassBackdropActivityRespectsPreviewTogglePlatformAndReadiness() {
        val enabled = LiquidGlassSurfaceOverrides(enabled = true)
        val disabled = LiquidGlassSurfaceOverrides(enabled = false)

        assertTrue(
            shouldUseLiquidGlassBackdrop(
                surface = LiquidGlassSurface.PLAYER,
                overrides = enabled,
                preview = true,
                masterEnabled = false,
                platformSupported = true,
                backdropReady = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                surface = LiquidGlassSurface.PLAYER,
                overrides = disabled,
                preview = true,
                masterEnabled = true,
                platformSupported = true,
                backdropReady = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                surface = LiquidGlassSurface.PLAYER,
                overrides = enabled,
                preview = false,
                masterEnabled = false,
                platformSupported = true,
                backdropReady = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                surface = LiquidGlassSurface.PLAYER,
                overrides = enabled,
                preview = true,
                masterEnabled = false,
                platformSupported = false,
                backdropReady = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassBackdrop(
                surface = LiquidGlassSurface.PLAYER,
                overrides = enabled,
                preview = true,
                masterEnabled = false,
                platformSupported = true,
                backdropReady = false,
            ),
        )
    }

    @Test
    fun previewMayBypassTheMasterSwitchButNeverPlatformSupport() {
        assertTrue(
            shouldUseLiquidGlassRenderer(
                preview = true,
                masterEnabled = false,
                platformSupported = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassRenderer(
                preview = true,
                masterEnabled = false,
                platformSupported = false,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassRenderer(
                preview = false,
                masterEnabled = false,
                platformSupported = true,
            ),
        )
        assertTrue(
            shouldUseLiquidGlassRenderer(
                preview = false,
                masterEnabled = true,
                platformSupported = true,
            ),
        )
        assertFalse(
            shouldUseLiquidGlassRenderer(
                preview = false,
                masterEnabled = true,
                platformSupported = false,
            ),
        )
    }

    @Test
    fun surfaceMaterialSelectionIsIndependentAndRoundTrips() {
        val initial = LiquidGlassSurfacesSettings()
        assertEquals(null, initial.forSurface(LiquidGlassSurface.SEARCH).material)

        val selected = initial.withSurface(
            LiquidGlassSurface.SEARCH,
            LiquidGlassSurfaceOverrides(material = LiquidGlassMaterial.CLEAR),
        )

        assertEquals(LiquidGlassMaterial.CLEAR, selected.forSurface(LiquidGlassSurface.SEARCH).material)
        assertEquals(null, selected.forSurface(LiquidGlassSurface.LIBRARY).material)
        assertEquals(selected, decodeLiquidGlassSurfaceSettings(encodeLiquidGlassSurfaceSettings(selected)))
    }

    @Test
    fun solidMaterialIsNotAvailableForSurfaceOverrides() {
        assertEquals(
            null,
            LiquidGlassSurfaceOverrides(material = LiquidGlassMaterial.SOLID).sanitized().material,
        )
    }

    @Test
    fun materialModesMatchNavigationBarBlurAndRefractionRules() {
        val liquid = LiquidGlassStyle(material = LiquidGlassMaterial.LIQUID)
        val frosted = LiquidGlassStyle(material = LiquidGlassMaterial.FROSTED)
        val clear = LiquidGlassStyle(material = LiquidGlassMaterial.CLEAR)

        assertEquals(true, liquid.usesBlur)
        assertEquals(true, liquid.usesLens)
        assertEquals(true, frosted.usesBlur)
        assertEquals(false, frosted.usesLens)
        assertEquals(false, clear.usesBlur)
        assertEquals(true, clear.usesLens)
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
