package com.nuvio.app.core.ui.glass

import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.ui.nuvio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.math.roundToInt
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal val GlassSurfaceColor = Color(0xFF1C1C1E)

/**
 * Existing platform-specific capsule surface used when the Liquid Glass material option is off.
 * The opt-in path below applies Kyant's full-capsule refraction and a separate animated selection lens.
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
 * Adapts Kyant0's LiquidBottomTabs surface effects to Nuvio's existing pill geometry:
 * vibrancy, background blur, full-capsule refraction, and a theme-aware translucent tint.
 * Nuvio's existing JellyMotion and glow remain responsible for navigation interaction.
 */
@Composable
internal fun LiquidGlassFrostedBar(
    backdrop: Backdrop,
    modifier: Modifier,
    glowStrength: Float,
    settings: LiquidGlassEffectSettings = LiquidGlassEffectDefaults.floatingBar,
) {
    val safeSettings = settings.sanitized(
        LiquidGlassEffectDefaults.floatingBar,
        LiquidGlassEffectDefaults.floatingBarLimits,
    )
    // Nuvio owns its palette independently from Android/iOS system appearance.
    // Tint the glass with the active Nuvio surface, not the device's light/dark setting.
    val containerColor = MaterialTheme.nuvio.colors.surface.copy(alpha = safeSettings.tintOpacity)
    val cornerShape = RoundedCornerShape(safeSettings.cornerRadiusPercent.roundToInt())

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { cornerShape },
                effects = {
                    vibrancy()
                    if (safeSettings.blurRadiusDp > 0f) {
                        blur(safeSettings.blurRadiusDp.dp.toPx())
                    }
                    if (safeSettings.refractionHeightDp > 0f && safeSettings.refractionAmountDp > 0f) {
                        lens(
                            refractionHeight = safeSettings.refractionHeightDp.dp.toPx(),
                            refractionAmount = safeSettings.refractionAmountDp.dp.toPx(),
                            depthEffect = safeSettings.depthEffect,
                            chromaticAberration = safeSettings.chromaticAberration,
                        )
                    }
                },
                onDrawSurface = {
                    drawRect(containerColor)
                },
            )
            .drawWithCache {
                // Match Nuvio's existing dark frosted-bar edge treatment regardless of system theme.
                val edgeTop = 0.27f
                val edge = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = edgeTop), Color.White.copy(alpha = 0.02f)),
                )
                val width = 0.75.dp.toPx()
                val cornerRadius = size.minDimension * safeSettings.cornerRadiusPercent / 100f
                onDrawWithContent {
                    drawContent()
                    drawRoundRect(
                        brush = edge,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius((cornerRadius - width / 2).coerceAtLeast(0f)),
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
    pressProgress: () -> Float,
    shape: () -> Shape,
    modifier: Modifier = Modifier,
    settings: LiquidGlassEffectSettings = LiquidGlassEffectDefaults.selectionLens,
) {
    val safeSettings = settings.sanitized(
        LiquidGlassEffectDefaults.selectionLens,
        LiquidGlassEffectDefaults.selectionLensLimits,
    )
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            effects = {
                if (safeSettings.blurRadiusDp > 0f) {
                    blur(safeSettings.blurRadiusDp.dp.toPx())
                }
                if (safeSettings.refractionHeightDp > 0f && safeSettings.refractionAmountDp > 0f) {
                    // Keep the configured lens active at rest so the settings controls have an
                    // immediate visible effect; pressProgress still drives the animated highlights
                    // and shadows below.
                    lens(
                        refractionHeight = safeSettings.refractionHeightDp.dp.toPx(),
                        refractionAmount = safeSettings.refractionAmountDp.dp.toPx(),
                        depthEffect = safeSettings.depthEffect,
                        chromaticAberration = safeSettings.chromaticAberration,
                    )
                }
            },
            highlight = {
                Highlight.Default.copy(alpha = pressProgress().coerceIn(0f, 1f))
            },
            shadow = {
                Shadow(alpha = pressProgress().coerceIn(0f, 1f))
            },
            innerShadow = {
                val progress = pressProgress().coerceIn(0f, 1f)
                InnerShadow(radius = 8f.dp * progress, alpha = progress)
            },
            onDrawSurface = {
                val progress = pressProgress().coerceIn(0f, 1f)
                drawRect(
                    Color.White.copy(alpha = safeSettings.tintOpacity),
                    alpha = 1f - progress,
                )
                drawRect(Color.Black.copy(alpha = 0.03f * progress))
            },
        ),
    )
}
