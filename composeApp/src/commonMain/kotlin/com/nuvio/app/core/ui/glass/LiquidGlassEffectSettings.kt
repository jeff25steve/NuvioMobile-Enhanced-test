package com.nuvio.app.core.ui.glass

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class LiquidGlassEffectSettings(
    val cornerRadiusPercent: Float,
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val chromaticAberration: Boolean,
    val depthEffect: Boolean,
    val tintOpacity: Float,
    val affectTabIcons: Boolean = false,
    val affectFloatingBar: Boolean = false,
) {
    fun sanitized(
        defaults: LiquidGlassEffectSettings,
        limits: LiquidGlassEffectLimits,
    ): LiquidGlassEffectSettings = copy(
        cornerRadiusPercent = safeRange(cornerRadiusPercent, 50f, defaults.cornerRadiusPercent),
        blurRadiusDp = safeRange(blurRadiusDp, limits.maxBlurRadiusDp, defaults.blurRadiusDp),
        refractionHeightDp = safeRange(
            refractionHeightDp,
            limits.maxRefractionHeightDp,
            defaults.refractionHeightDp,
        ),
        refractionAmountDp = safeRange(
            refractionAmountDp,
            limits.maxRefractionAmountDp,
            defaults.refractionAmountDp,
        ),
        tintOpacity = safeRange(tintOpacity, 1f, defaults.tintOpacity),
    )

    private fun safeRange(value: Float, maximum: Float, fallback: Float): Float =
        (value.takeIf { it.isFinite() } ?: fallback).coerceIn(0f, maximum)
}

internal data class LiquidGlassEffectLimits(
    val maxBlurRadiusDp: Float,
    val maxRefractionHeightDp: Float,
    val maxRefractionAmountDp: Float,
)

/**
 * A tab-icon backdrop source is useful only while the selection lens has an active visual
 * effect. If refraction is disabled (height or amount is zero) and blur is zero, keep the
 * regular selected icon visible instead of sampling a duplicate, unfiltered copy.
 */
internal fun LiquidGlassEffectSettings.hasActiveLensEffect(): Boolean =
    blurRadiusDp > 0f || (refractionHeightDp > 0f && refractionAmountDp > 0f)

internal fun LiquidGlassEffectSettings.shouldCaptureTabIcons(): Boolean =
    affectTabIcons && hasActiveLensEffect()

internal object LiquidGlassEffectDefaults {
    val floatingBar = LiquidGlassEffectSettings(
        cornerRadiusPercent = 50f,
        blurRadiusDp = 8f,
        refractionHeightDp = 24f,
        refractionAmountDp = 24f,
        chromaticAberration = false,
        depthEffect = false,
        tintOpacity = 0.4f,
    )
    val selectionLens = LiquidGlassEffectSettings(
        cornerRadiusPercent = 50f,
        blurRadiusDp = 0f,
        refractionHeightDp = 10f,
        refractionAmountDp = 14f,
        chromaticAberration = true,
        depthEffect = false,
        tintOpacity = 0.1f,
    )

    val floatingBarLimits = LiquidGlassEffectLimits(
        maxBlurRadiusDp = 32f,
        maxRefractionHeightDp = 48f,
        maxRefractionAmountDp = 48f,
    )
    val selectionLensLimits = LiquidGlassEffectLimits(
        maxBlurRadiusDp = 24f,
        maxRefractionHeightDp = 32f,
        maxRefractionAmountDp = 32f,
    )
}

private val liquidGlassSettingsJson = Json {
    ignoreUnknownKeys = true
}

internal fun encodeLiquidGlassEffectSettings(
    settings: LiquidGlassEffectSettings,
    defaults: LiquidGlassEffectSettings,
    limits: LiquidGlassEffectLimits,
): String = liquidGlassSettingsJson.encodeToString(settings.sanitized(defaults, limits))

internal fun decodeLiquidGlassEffectSettings(
    encoded: String?,
    defaults: LiquidGlassEffectSettings,
    limits: LiquidGlassEffectLimits,
): LiquidGlassEffectSettings {
    if (encoded.isNullOrBlank()) return defaults

    val values = runCatching {
        liquidGlassSettingsJson.parseToJsonElement(encoded).jsonObject
    }.getOrNull() ?: return defaults

    return defaults.copy(
        cornerRadiusPercent = values.floatOrDefault("cornerRadiusPercent", defaults.cornerRadiusPercent),
        blurRadiusDp = values.floatOrDefault("blurRadiusDp", defaults.blurRadiusDp),
        refractionHeightDp = values.floatOrDefault("refractionHeightDp", defaults.refractionHeightDp),
        refractionAmountDp = values.floatOrDefault("refractionAmountDp", defaults.refractionAmountDp),
        chromaticAberration = values.booleanOrDefault("chromaticAberration", defaults.chromaticAberration),
        depthEffect = values.booleanOrDefault("depthEffect", defaults.depthEffect),
        tintOpacity = values.floatOrDefault("tintOpacity", defaults.tintOpacity),
        affectTabIcons = values.booleanOrDefault("affectTabIcons", defaults.affectTabIcons),
        affectFloatingBar = values.booleanOrDefault("affectFloatingBar", defaults.affectFloatingBar),
    ).sanitized(defaults, limits)
}

private fun kotlinx.serialization.json.JsonObject.floatOrDefault(
    key: String,
    default: Float,
): Float {
    val value = runCatching { this[key]?.jsonPrimitive?.floatOrNull }.getOrNull()
    return value?.takeIf { it.isFinite() } ?: default
}

private fun kotlinx.serialization.json.JsonObject.booleanOrDefault(
    key: String,
    default: Boolean,
): Boolean = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: default
