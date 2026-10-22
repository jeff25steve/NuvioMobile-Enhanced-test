package com.nuvio.app.core.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.onDispose
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
    STREAM_SELECTION,
    DETAILS,
    SEARCH,
    LIBRARY,
    SETTINGS,
    BOTTOM_SHEETS;

    val defaultEnabled: Boolean
        get() = when (this) {
            PLAYER, STREAM_SELECTION, DETAILS, BOTTOM_SHEETS -> true
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
}

@Serializable
internal data class LiquidGlassSurfacesSettings(
    val player: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val streamSelection: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val details: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val search: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val library: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val settings: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
    val bottomSheets: LiquidGlassSurfaceOverrides = LiquidGlassSurfaceOverrides(),
) {
    fun forSurface(surface: LiquidGlassSurface): LiquidGlassSurfaceOverrides = when (surface) {
        LiquidGlassSurface.PLAYER -> player
        LiquidGlassSurface.STREAM_SELECTION -> streamSelection
        LiquidGlassSurface.DETAILS -> details
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
            LiquidGlassSurface.STREAM_SELECTION -> copy(streamSelection = safe)
            LiquidGlassSurface.DETAILS -> copy(details = safe)
            LiquidGlassSurface.SEARCH -> copy(search = safe)
            LiquidGlassSurface.LIBRARY -> copy(library = safe)
            LiquidGlassSurface.SETTINGS -> copy(settings = safe)
            LiquidGlassSurface.BOTTOM_SHEETS -> copy(bottomSheets = safe)
        }
    }

    fun sanitized(): LiquidGlassSurfacesSettings = LiquidGlassSurfacesSettings(
        player = player.sanitized(),
        streamSelection = streamSelection.sanitized(),
        details = details.sanitized(),
        search = search.sanitized(),
        library = library.sanitized(),
        settings = settings.sanitized(),
        bottomSheets = bottomSheets.sanitized(),
    )
}

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
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    if (!preview && (!LiquidGlassSettings.enabled || !(overrides.enabled ?: surface.defaultEnabled))) {
        return defaultShape
    }
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
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
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
            withVibrancy = withVibrancy,
            withHighlight = withHighlight,
            withShadow = withShadow,
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
            withVibrancy = withVibrancy,
            withHighlight = withHighlight,
            withShadow = withShadow,
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
    if (!preview && (!isLiquidGlassSupported() || !LiquidGlassSettings.enabled)) return default
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    if (!preview && !(overrides.enabled ?: surface.defaultEnabled)) return default
    return overrides.defaultIconColorArgb?.let { Color(it).copy(alpha = default.alpha) } ?: default
}

/** Used by components that need to fall back to their original control when a surface is disabled. */
internal fun isLiquidGlassSurfacePreferenceEnabled(surface: LiquidGlassSurface): Boolean {
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    return overrides.enabled ?: surface.defaultEnabled
}
