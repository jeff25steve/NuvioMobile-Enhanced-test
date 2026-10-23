package com.nuvio.app.core.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Independently configurable app areas. Navigation-bar glass intentionally remains on its existing
 * settings/model so its per-piece defaults and preview are not changed by these preferences.
 */
@Serializable
internal enum class LiquidGlassSurface {
    PLAYER,
    STREAM_FILTERS,
    STREAM_SELECTION,
    STREAM_RESULTS,
    DETAILS,
    DETAIL_HEADER,
    DETAIL_CHIPS,
    DETAIL_CONTENT_CARDS,
    DETAIL_PRIMARY_ACTION,
    SEARCH,
    LIBRARY,
    SETTINGS,
    BOTTOM_SHEETS;

    val defaultEnabled: Boolean
        get() = when (this) {
            PLAYER,
            STREAM_FILTERS,
            STREAM_SELECTION,
            STREAM_RESULTS,
            DETAILS,
            DETAIL_HEADER,
            DETAIL_CHIPS,
            DETAIL_CONTENT_CARDS,
            DETAIL_PRIMARY_ACTION,
            BOTTOM_SHEETS,
            -> true
            SEARCH, LIBRARY, SETTINGS -> false
        }
}

/**
 * Nullable values inherit the exact recipe at each call site. That is important for surfaces such
 * as the player where different controls intentionally use different blur and refraction values.
 */
@Serializable
internal data class LiquidGlassSurfaceOverrides(
    val enabled: Boolean? = null,
    val cornerRadiusPercent: Float? = null,
    val blurRadiusDp: Float? = null,
    val refractionHeightDp: Float? = null,
    val refractionAmountDp: Float? = null,
    val tintOpacity: Float? = null,
    val withVibrancy: Boolean? = null,
    val withHighlight: Boolean? = null,
    val withShadow: Boolean? = null,
    val chromaticAberration: Boolean? = null,
    val depthEffect: Boolean? = null,
    /** Opaque ARGB. A null value means use the screen's original icon color. */
    val defaultIconColorArgb: Int? = null,
) {
    fun sanitized(): LiquidGlassSurfaceOverrides = copy(
        cornerRadiusPercent = cornerRadiusPercent.inheritOrClamp(0f, 50f),
        blurRadiusDp = blurRadiusDp.inheritOrClamp(0f, 32f),
        refractionHeightDp = refractionHeightDp.inheritOrClamp(0f, 48f),
        refractionAmountDp = refractionAmountDp.inheritOrClamp(0f, 48f),
        tintOpacity = tintOpacity.inheritOrClamp(0f, 1f),
        defaultIconColorArgb = defaultIconColorArgb?.let { it or 0xFF000000.toInt() },
    )

    private fun Float?.inheritOrClamp(minimum: Float, maximum: Float): Float? =
        this?.takeIf { it.isFinite() }?.coerceIn(minimum, maximum)

    /**
     * Links material behavior while deliberately preserving component-specific geometry and icon
     * color. Play buttons, chips, cards, and rectangular headers keep their original silhouettes.
     */
    fun withGlassEffectFrom(shared: LiquidGlassSurfaceOverrides): LiquidGlassSurfaceOverrides = copy(
        enabled = shared.enabled,
        blurRadiusDp = shared.blurRadiusDp,
        refractionHeightDp = shared.refractionHeightDp,
        refractionAmountDp = shared.refractionAmountDp,
        tintOpacity = shared.tintOpacity,
        withVibrancy = shared.withVibrancy,
        withHighlight = shared.withHighlight,
        withShadow = shared.withShadow,
        chromaticAberration = shared.chromaticAberration,
        depthEffect = shared.depthEffect,
    ).sanitized()
}

/** A composite, production-shaped preview that lets users tune related surfaces together. */
internal enum class LiquidGlassSurfaceGroup(val surfaces: List<LiquidGlassSurface>) {
    DETAIL_PAGE(
        listOf(
            LiquidGlassSurface.DETAIL_HEADER,
            LiquidGlassSurface.DETAILS,
            LiquidGlassSurface.DETAIL_PRIMARY_ACTION,
            LiquidGlassSurface.DETAIL_CHIPS,
            LiquidGlassSurface.DETAIL_CONTENT_CARDS,
        ),
    ),
    STREAM_PICKER(
        listOf(
            LiquidGlassSurface.STREAM_FILTERS,
            LiquidGlassSurface.STREAM_SELECTION,
            LiquidGlassSurface.STREAM_RESULTS,
        ),
    ),
}

@Serializable
internal data class LiquidGlassSurfacesSettings(
    val player: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    // Provider chips already used glass by default; this separate setting preserves that behavior.
    val streamFilters: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val streamSelection: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val details: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    // Missing fields in existing saved JSON inherit the old shared recipe during deserialization.
    val detailHeader: LiquidGlassSurfaceOverrides = details,
    val detailChips: LiquidGlassSurfaceOverrides = details,
    val detailContentCards: LiquidGlassSurfaceOverrides = details,
    val detailPrimaryAction: LiquidGlassSurfaceOverrides = details,
    val streamResults: LiquidGlassSurfaceOverrides = streamSelection,
    val search: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val library: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val settings: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val bottomSheets: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
) {
    fun forSurface(surface: LiquidGlassSurface): LiquidGlassSurfaceOverrides = when (surface) {
        LiquidGlassSurface.PLAYER -> player
        LiquidGlassSurface.STREAM_FILTERS -> streamFilters
        LiquidGlassSurface.STREAM_SELECTION -> streamSelection
        LiquidGlassSurface.STREAM_RESULTS -> streamResults
        LiquidGlassSurface.DETAILS -> details
        LiquidGlassSurface.DETAIL_HEADER -> detailHeader
        LiquidGlassSurface.DETAIL_CHIPS -> detailChips
        LiquidGlassSurface.DETAIL_CONTENT_CARDS -> detailContentCards
        LiquidGlassSurface.DETAIL_PRIMARY_ACTION -> detailPrimaryAction
        LiquidGlassSurface.SEARCH -> search
        LiquidGlassSurface.LIBRARY -> library
        LiquidGlassSurface.SETTINGS -> settings
        LiquidGlassSurface.BOTTOM_SHEETS -> bottomSheets
    }

    fun withSurface(
        surface: LiquidGlassSurface,
        overrides: LiquidGlassSurfaceOverrides,
    ): LiquidGlassSurfacesSettings {
        val safe = overrides.sanitized()
        return when (surface) {
            LiquidGlassSurface.PLAYER -> copy(player = safe)
            LiquidGlassSurface.STREAM_FILTERS -> copy(streamFilters = safe)
            LiquidGlassSurface.STREAM_SELECTION -> copy(streamSelection = safe)
            LiquidGlassSurface.STREAM_RESULTS -> copy(streamResults = safe)
            LiquidGlassSurface.DETAILS -> copy(details = safe)
            LiquidGlassSurface.DETAIL_HEADER -> copy(detailHeader = safe)
            LiquidGlassSurface.DETAIL_CHIPS -> copy(detailChips = safe)
            LiquidGlassSurface.DETAIL_CONTENT_CARDS -> copy(detailContentCards = safe)
            LiquidGlassSurface.DETAIL_PRIMARY_ACTION -> copy(detailPrimaryAction = safe)
            LiquidGlassSurface.SEARCH -> copy(search = safe)
            LiquidGlassSurface.LIBRARY -> copy(library = safe)
            LiquidGlassSurface.SETTINGS -> copy(settings = safe)
            LiquidGlassSurface.BOTTOM_SHEETS -> copy(bottomSheets = safe)
        }
    }

    fun sanitized(): LiquidGlassSurfacesSettings = LiquidGlassSurfacesSettings(
        player = player.sanitized(),
        streamFilters = streamFilters.sanitized(),
        streamSelection = streamSelection.sanitized(),
        details = details.sanitized(),
        detailHeader = detailHeader.sanitized(),
        detailChips = detailChips.sanitized(),
        detailContentCards = detailContentCards.sanitized(),
        detailPrimaryAction = detailPrimaryAction.sanitized(),
        streamResults = streamResults.sanitized(),
        search = search.sanitized(),
        library = library.sanitized(),
        settings = settings.sanitized(),
        bottomSheets = bottomSheets.sanitized(),
    )
}

/**
 * Non-persistent preview-only overrides let production controls render their exact shapes and
 * material values inside the editor without mutating the real app-wide settings.
 */
internal val LocalLiquidGlassPreviewOverrides = staticCompositionLocalOf<LiquidGlassSurfaceOverrides?> { null }

/**
 * Complete preview-only settings for an interactive layout. Each production component resolves its
 * own surface from this value, so tuning one region does not leak into neighboring preview regions.
 */
internal val LocalLiquidGlassPreviewSettings = staticCompositionLocalOf<LiquidGlassSurfacesSettings?> { null }

private val liquidGlassSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

internal fun encodeLiquidGlassSurfaceSettings(settings: LiquidGlassSurfacesSettings): String =
    liquidGlassSettingsJson.encodeToString(settings.sanitized())

internal fun decodeLiquidGlassSurfaceSettings(raw: String?): LiquidGlassSurfacesSettings {
    if (raw.isNullOrBlank()) return LiquidGlassSurfacesSettings()
    return runCatching {
        liquidGlassSettingsJson.decodeFromString<LiquidGlassSurfacesSettings>(raw).sanitized()
    }.getOrElse { LiquidGlassSurfacesSettings() }
}

/**
 * Uses existing call-site values unless the user has explicitly overridden them. Existing glass
 * recipes therefore remain pixel-for-pixel unchanged until that area is customized.
 */
@Composable
internal fun liquidGlassShape(
    surface: LiquidGlassSurface,
    defaultShape: Shape,
    preview: Boolean = false,
): Shape {
    val overrides = if (preview) {
        LocalLiquidGlassPreviewSettings.current?.forSurface(surface)
            ?: LocalLiquidGlassPreviewOverrides.current
            ?: LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    } else {
        LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    }
    if (!preview && (!LiquidGlassSettings.enabled || !(overrides.enabled ?: surface.defaultEnabled))) {
        return defaultShape
    }
    // Rectangular scroll-edge headers are intentionally full-width; a migrated detail radius
    // setting must never turn this shape into a rounded card.
    if (defaultShape === RectangleShape) return defaultShape
    return overrides.cornerRadiusPercent?.let { percent ->
        RoundedCornerShape(percent = percent.roundToInt())
    } ?: defaultShape
}

@Composable
internal fun Modifier.liquidGlassForSurface(
    surface: LiquidGlassSurface,
    shape: Shape,
    tint: Color,
    fallback: Color,
    blurRadius: Dp = 16.dp,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = refractionHeight * 1.6f,
    withVibrancy: Boolean = true,
    withHighlight: Boolean = true,
    withShadow: Boolean = false,
    backdropOpacity: Float = 1f,
    fallbackBrush: Brush? = null,
    explicitBackdrop: Backdrop? = null,
    useExplicitBackdrop: Boolean = false,
    preview: Boolean = false,
): Modifier {
    val overrides = if (preview) {
        LocalLiquidGlassPreviewSettings.current?.forSurface(surface)
            ?: LocalLiquidGlassPreviewOverrides.current
            ?: LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    } else {
        LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    }
    val enabled = preview || (LiquidGlassSettings.enabled && (overrides.enabled ?: surface.defaultEnabled))
    val effectiveShape = if (enabled) liquidGlassShape(surface, shape, preview = preview) else shape
    if (!enabled) {
        return if (fallbackBrush != null) {
            this.clip(effectiveShape).background(fallbackBrush, effectiveShape)
        } else {
            this.clip(effectiveShape).background(fallback, effectiveShape)
        }
    }

    val effectiveTint = overrides.tintOpacity?.let { tint.copy(alpha = it) } ?: tint
    val effectiveBlur = overrides.blurRadiusDp?.dp ?: blurRadius
    val effectiveRefractionHeight = overrides.refractionHeightDp?.dp ?: refractionHeight
    val effectiveRefractionAmount = overrides.refractionAmountDp?.dp ?: refractionAmount
    val effectiveBackdropOpacity = backdropOpacity
    val effectiveFallbackBrush = fallbackBrush

    return if (useExplicitBackdrop) {
        val host = explicitBackdrop as? LiquidGlassBackdropHost
        if (host != null) {
            DisposableEffect(host) {
                host.acquire()
                onDispose { host.release() }
            }
        }
        liquidGlassSurface(
            backdrop = explicitBackdrop?.takeIf { (host?.isReady ?: true) },
            shape = effectiveShape,
            tint = effectiveTint,
            fallback = fallback,
            blurRadius = effectiveBlur,
            refractionHeight = effectiveRefractionHeight,
            refractionAmount = effectiveRefractionAmount,
            withVibrancy = overrides.withVibrancy ?: withVibrancy,
            withHighlight = overrides.withHighlight ?: withHighlight,
            withShadow = overrides.withShadow ?: withShadow,
            fallbackBrush = effectiveFallbackBrush,
            backdropOpacity = effectiveBackdropOpacity,
            chromaticAberration = overrides.chromaticAberration ?: false,
            depthEffect = overrides.depthEffect ?: false,
        ).let { this.clip(effectiveShape).then(it) }
    } else {
        this.clip(effectiveShape).ambientLiquidGlass(
            shape = effectiveShape,
            tint = effectiveTint,
            fallback = fallback,
            blurRadius = effectiveBlur,
            refractionHeight = effectiveRefractionHeight,
            refractionAmount = effectiveRefractionAmount,
            withVibrancy = overrides.withVibrancy ?: withVibrancy,
            withHighlight = overrides.withHighlight ?: withHighlight,
            withShadow = overrides.withShadow ?: withShadow,
            fallbackBrush = effectiveFallbackBrush,
            backdropOpacity = effectiveBackdropOpacity,
            chromaticAberration = overrides.chromaticAberration ?: false,
            depthEffect = overrides.depthEffect ?: false,
        )
    }
}

@Composable
internal fun liquidGlassIconTint(
    surface: LiquidGlassSurface,
    default: Color,
    preview: Boolean = false,
): Color {
    val previewOverrides = if (preview) {
        LocalLiquidGlassPreviewSettings.current?.forSurface(surface)
            ?: LocalLiquidGlassPreviewOverrides.current
    } else {
        null
    }
    if (previewOverrides == null && !preview &&
        (!isLiquidGlassSupported() || !LiquidGlassSettings.enabled)
    ) {
        return default
    }
    val overrides = previewOverrides ?: LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    if (previewOverrides == null && !preview &&
        !(overrides.enabled ?: surface.defaultEnabled)
    ) {
        return default
    }
    return overrides.defaultIconColorArgb?.let { Color(it).copy(alpha = default.alpha) } ?: default
}

/** Used by components that need to fall back to their original control when a surface is disabled. */
internal fun isLiquidGlassSurfacePreferenceEnabled(surface: LiquidGlassSurface): Boolean {
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    return overrides.enabled ?: surface.defaultEnabled
}
