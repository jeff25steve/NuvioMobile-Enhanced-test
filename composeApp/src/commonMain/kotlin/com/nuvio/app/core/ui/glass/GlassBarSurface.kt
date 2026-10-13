package com.nuvio.app.core.ui.glass

import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.Backdrop
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal val GlassSurfaceColor = Color(0xFF1C1C1E)

/**
 * Existing platform-specific capsule surface used when the Liquid Glass material option is off.
 * The opt-in material keeps a frosted capsule and draws Kyant's refractive lens only on selection.
 */
@Composable
internal expect fun GlassBarSurface(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    glowStrength: Float = 1f,
)

/** Haze blur, a dark fill and a thin top-lit edge: the glass bar without the refraction shader. */
@Composable
internal fun FrostedGlassBar(hazeState: HazeState?, modifier: Modifier, glowStrength: Float) {
    Box(
        modifier
            .then(if (hazeState != null) Modifier.barBackdrop(hazeState) else Modifier)
            .drawWithCache {
                val fill = GlassSurfaceColor.copy(alpha = if (hazeState != null) 0.55f else 0.82f)
                val edge = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)),
                )
                val width = 0.75.dp.toPx()
                onDrawBehind {
                    drawRect(fill)
                    drawRoundRect(
                        brush = edge,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius((size.height - width) / 2),
                        style = Stroke(width),
                        alpha = glowStrength,
                    )
                }
            },
    )
}

internal fun Modifier.barBackdrop(hazeState: HazeState): Modifier = hazeEffect(state = hazeState) {
    blurRadius = 24.dp
    backgroundColor = GlassSurfaceColor
    tints = listOf(HazeTint(Color.Transparent))
    noiseFactor = 0f
}

/**
 * Frosted capsule used behind the separate refractive selection lens.
 * Keeps the existing Haze blur while using a theme-appropriate translucent tint.
 */
@Composable
internal fun LiquidGlassFrostedBar(hazeState: HazeState?, modifier: Modifier, glowStrength: Float) {
    val isLightTheme = !isSystemInDarkTheme()
    val surfaceColor = if (isLightTheme) Color(0xFFFAFAFA) else Color(0xFF121212)

    Box(
        modifier
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 24.dp
                        backgroundColor = surfaceColor
                        tints = listOf(HazeTint(Color.Transparent))
                        noiseFactor = 0f
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val fill = surfaceColor.copy(alpha = if (hazeState != null) 0.4f else 0.82f)
                val edgeTop = if (isLightTheme) 0.55f else 0.27f
                val edge = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = edgeTop), Color.White.copy(alpha = 0.02f)),
                )
                val width = 0.75.dp.toPx()
                onDrawBehind {
                    drawRect(fill)
                    drawRoundRect(
                        brush = edge,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius((size.height - width) / 2),
                        style = Stroke(width),
                        alpha = glowStrength,
                    )
                }
            },
    )
}

/**
 * Refractive selected-tab lens based on Kyant0's LiquidBottomTabs implementation.
 * The frosted capsule stays behind it; Nuvio continues to own the tab icons and labels.
 */
@Composable
internal fun LiquidGlassSelectionSurface(
    backdrop: Backdrop,
    pressProgress: Float,
    shape: () -> Shape,
    modifier: Modifier = Modifier,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val progress = pressProgress.coerceIn(0f, 1f)
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            effects = {
                lens(
                    10f.dp.toPx() * progress,
                    14f.dp.toPx() * progress,
                    chromaticAberration = true,
                )
            },
            highlight = {
                Highlight.Default.copy(alpha = progress)
            },
            shadow = {
                Shadow(alpha = progress)
            },
            innerShadow = {
                InnerShadow(radius = 8f.dp * progress, alpha = progress)
            },
            onDrawSurface = {
                drawRect(
                    if (isLightTheme) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                    alpha = 1f - progress,
                )
                drawRect(Color.Black.copy(alpha = 0.03f * progress))
            },
        ),
    )
}
