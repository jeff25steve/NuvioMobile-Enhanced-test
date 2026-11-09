package com.nuvio.app.core.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.NuvioThemeTokens
import com.nuvio.app.core.ui.nuvio

private val LiquidGlassReadableTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.68f),
    offset = Offset(0f, 1f),
    blurRadius = 3f,
)

/**
 * Brightens neutral text tokens used inside glass while leaving surfaces, status colors and
 * accents alone. The current Nuvio theme is dark; these values retain its hierarchy while lifting
 * secondary and muted text out of the grey range that can disappear over bright artwork.
 */
internal fun NuvioThemeTokens.withLiquidGlassTextContrast(enabled: Boolean): NuvioThemeTokens =
    if (!enabled) {
        this
    } else {
        copy(
            colors = colors.copy(
                textPrimary = Color.White,
                textSecondary = Color(0xFFE1E6EB),
                textMuted = Color(0xFFCBD2D9),
                textDisabled = Color(0xFFADB6BF),
            ),
        )
    }

/**
 * Adds a small dark edge that separates glyphs from bright or detailed pixels beneath transparent
 * glass. Keep the blur low so it improves separation without making text look outlined or fuzzy.
 */
internal fun TextStyle.withLiquidGlassTextShadow(enabled: Boolean): TextStyle =
    if (enabled) copy(shadow = shadow ?: LiquidGlassReadableTextShadow) else this


/**
 * Explicit `Text(style = MaterialTheme.typography.*)` calls do not use LocalTextStyle.current.
 * Transform the whole Material3 type scale so those texts receive the same readable edge as
 * components that rely on the inherited LocalTextStyle.
 */
internal fun Typography.withLiquidGlassTextShadow(enabled: Boolean): Typography =
    if (!enabled) {
        this
    } else {
        copy(
            displayLarge = displayLarge.withLiquidGlassTextShadow(true),
            displayMedium = displayMedium.withLiquidGlassTextShadow(true),
            displaySmall = displaySmall.withLiquidGlassTextShadow(true),
            headlineLarge = headlineLarge.withLiquidGlassTextShadow(true),
            headlineMedium = headlineMedium.withLiquidGlassTextShadow(true),
            headlineSmall = headlineSmall.withLiquidGlassTextShadow(true),
            titleLarge = titleLarge.withLiquidGlassTextShadow(true),
            titleMedium = titleMedium.withLiquidGlassTextShadow(true),
            titleSmall = titleSmall.withLiquidGlassTextShadow(true),
            bodyLarge = bodyLarge.withLiquidGlassTextShadow(true),
            bodyMedium = bodyMedium.withLiquidGlassTextShadow(true),
            bodySmall = bodySmall.withLiquidGlassTextShadow(true),
            labelLarge = labelLarge.withLiquidGlassTextShadow(true),
            labelMedium = labelMedium.withLiquidGlassTextShadow(true),
            labelSmall = labelSmall.withLiquidGlassTextShadow(true),
            displayLargeEmphasized = displayLargeEmphasized.withLiquidGlassTextShadow(true),
            displayMediumEmphasized = displayMediumEmphasized.withLiquidGlassTextShadow(true),
            displaySmallEmphasized = displaySmallEmphasized.withLiquidGlassTextShadow(true),
            headlineLargeEmphasized = headlineLargeEmphasized.withLiquidGlassTextShadow(true),
            headlineMediumEmphasized = headlineMediumEmphasized.withLiquidGlassTextShadow(true),
            headlineSmallEmphasized = headlineSmallEmphasized.withLiquidGlassTextShadow(true),
            titleLargeEmphasized = titleLargeEmphasized.withLiquidGlassTextShadow(true),
            titleMediumEmphasized = titleMediumEmphasized.withLiquidGlassTextShadow(true),
            titleSmallEmphasized = titleSmallEmphasized.withLiquidGlassTextShadow(true),
            bodyLargeEmphasized = bodyLargeEmphasized.withLiquidGlassTextShadow(true),
            bodyMediumEmphasized = bodyMediumEmphasized.withLiquidGlassTextShadow(true),
            bodySmallEmphasized = bodySmallEmphasized.withLiquidGlassTextShadow(true),
            labelLargeEmphasized = labelLargeEmphasized.withLiquidGlassTextShadow(true),
            labelMediumEmphasized = labelMediumEmphasized.withLiquidGlassTextShadow(true),
            labelSmallEmphasized = labelSmallEmphasized.withLiquidGlassTextShadow(true),
        )
    }

/**
 * Material3 text colors are used directly by many controls, so update their semantic foreground
 * colors alongside Nuvio's own text tokens. Backgrounds, accent colors and state colors are kept
 * unchanged.
 */
internal fun ColorScheme.withLiquidGlassTextContrast(enabled: Boolean): ColorScheme =
    if (!enabled) {
        this
    } else {
        copy(
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFE1E6EB),
            onBackground = Color.White,
        )
    }

/**
 * Apply Liquid Glass's readable text treatment only to the content sitting on the glass surface.
 * Callers must scope this around the surface's actual content, not an entire page with unrelated
 * text or media. Preview and runtime use this exact same helper.
 */
@Composable
internal fun LiquidGlassTextContrastScope(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    // Capture before nesting MaterialTheme: MaterialTheme resets LocalTextStyle to bodyLarge.
    // Explicit MaterialTheme.typography styles are transformed separately so both explicit and
    // inherited Text calls keep their original type hierarchy and gain the same readable edge.
    val sourceTextStyle = LocalTextStyle.current
    val sourceTypography = MaterialTheme.typography.withLiquidGlassTextShadow(enabled = true)
    val sourceShapes = MaterialTheme.shapes
    val readableTokens = MaterialTheme.nuvio.withLiquidGlassTextContrast(enabled = true)
    val readableScheme = MaterialTheme.colorScheme.withLiquidGlassTextContrast(enabled = true)

    MaterialTheme(
        colorScheme = readableScheme,
        typography = sourceTypography,
        shapes = sourceShapes,
    ) {
        CompositionLocalProvider(
            LocalNuvioThemeTokens provides readableTokens,
            LocalTextStyle provides sourceTextStyle.withLiquidGlassTextShadow(enabled = true),
        ) {
            content()
        }
    }
}
