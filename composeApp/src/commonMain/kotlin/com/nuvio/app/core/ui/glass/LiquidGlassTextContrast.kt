package com.nuvio.app.core.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.dp
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
                neutral = Color(0xFFCBD2D9),
            ),
        )
    }

/**
 * Adds a small dark edge that separates glyphs from bright or detailed pixels beneath transparent
 * glass. Keep the blur low so it improves separation without making text look outlined or fuzzy.
 */
internal fun TextStyle.withLiquidGlassTextShadow(enabled: Boolean): TextStyle =
    if (enabled) copy(shadow = LiquidGlassReadableTextShadow) else this

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

    val sourceTypography = MaterialTheme.typography
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
            LocalTextStyle provides LocalTextStyle.current.withLiquidGlassTextShadow(enabled = true),
        ) {
            content()
        }
    }
}
