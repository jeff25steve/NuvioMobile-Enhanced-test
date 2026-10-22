package com.nuvio.app.core.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
    SETTINGS;

    val defaultEnabled: Boolean
        get() = when (this) {
            PLAYER, STREAM_SELECTION, DETAILS -> true
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
) {
    fun forSurface(surface: LiquidGlassSurface): LiquidGlassSurfaceOverrides = when (surface) {
        LiquidGlassSurface.PLAYER -> player
        LiquidGlassSurface.STREAM_SELECTION -> streamSelection
        LiquidGlassSurface.DETAILS -> details
        LiquidGlassSurface.SEARCH -> search
        LiquidGlassSurface.LIBRARY -> library
        LiquidGlassSurface.SETTINGS -> settings
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
        }
    }

    fun sanitized(): LiquidGlassSurfacesSettings = LiquidGlassSurfacesSettings(
        player = player.sanitized(),
        streamSelection = streamSelection.sanitized(),
        details = details.sanitized(),
        search = search.sanitized(),
        library = library.sanitized(),
        settings = settings.sanitized(),
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
internal fun liquidGlassShape(surface: LiquidGlassSurface, defaultShape: Shape): Shape =
    LiquidGlassSettings.surfaceOverrides.forSurface(surface).cornerRadiusPercent?.let { percent ->
        RoundedCornerShape(percent = percent.roundToInt())
    } ?: defaultShape

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
    preview: Boolean = false,
): Modifier {
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    if (!preview && (!LiquidGlassSettings.enabled || !(overrides.enabled ?: surface.defaultEnabled))) return this

    val effectiveShape = liquidGlassShape(surface, shape)
    val effectiveTint = overrides.tintOpacity?.let { tint.copy(alpha = it) } ?: tint

    val baseModifier = if (overrides.cornerRadiusPercent != null) this.clip(effectiveShape) else this
    return baseModifier.ambientLiquidGlass(
        shape = effectiveShape,
        tint = effectiveTint,
        fallback = fallback,
        blurRadius = overrides.blurRadiusDp?.dp ?: blurRadius,
        refractionHeight = overrides.refractionHeightDp?.dp ?: refractionHeight,
        refractionAmount = overrides.refractionAmountDp?.dp ?: refractionAmount,
        withVibrancy = withVibrancy,
        withHighlight = withHighlight,
        withShadow = withShadow,
        backdropOpacity = backdropOpacity,
        chromaticAberration = overrides.chromaticAberration ?: false,
        depthEffect = overrides.depthEffect ?: false,
    )
}

@Composable
internal fun liquidGlassIconTint(
    surface: LiquidGlassSurface,
    default: Color,
    preview: Boolean = false,
): Color {
    if (!preview && !LiquidGlassSettings.enabled) return default
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    if (!preview && !(overrides.enabled ?: surface.defaultEnabled)) return default
    return overrides.defaultIconColorArgb?.let { Color(it).copy(alpha = default.alpha) } ?: default
}

/** Used by components that need to fall back to their original control when a surface is disabled. */
internal fun isLiquidGlassSurfacePreferenceEnabled(surface: LiquidGlassSurface): Boolean {
    val overrides = LiquidGlassSettings.surfaceOverrides.forSurface(surface)
    return overrides.enabled ?: surface.defaultEnabled
}
