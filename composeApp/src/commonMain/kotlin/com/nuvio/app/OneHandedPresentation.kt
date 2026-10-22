package com.nuvio.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.nuvio.app.core.ui.glass.NuvioBackdrop
import com.nuvio.app.core.ui.glass.rememberNuvioBackdrop
import com.nuvio.app.core.ui.glass.nuvioBackdropSource

private const val OneHandedScale = 0.72f
private const val OneHandedMaxWidthDp = 600f
private const val OneHandedMaxHeightDp = 760f
private const val OneHandedMinimumScale = 0.60f
internal const val OneHandedMinimumViewportScale = 0.42f
internal const val OneHandedMaximumViewportScale = 0.86f
private const val OneHandedCompactScale = 0.76f
private const val OneHandedCompactWidthDp = 360f
private const val OneHandedCompactHeightDp = 640f

private const val OneHandedSpringDamping = Spring.DampingRatioNoBouncy
private const val OneHandedSpringStiffness = Spring.StiffnessMedium

/**
 * Draws the current, unscaled app layer at viewport origin. The source is recorded by
 * NuvioBackdropSourceNode from the existing UI tree; this adapter deliberately ignores the
 * transformed source coordinates because the recorded layer represents the full viewport.
 */
private class OneHandedLiveBackdrop(
    private val source: NuvioBackdrop,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        drawLayer(source.graphicsLayer)
    }
}

/**
 * Presents the existing UI tree as a single reduced, bottom-anchored surface.
 *
 * No duplicate composition or screenshot is retained. The outside area uses a theme-aware
 * frosted gradient fallback, so it remains safe for protected video surfaces and works on
 * Android and iOS without platform screenshot permissions or APIs.
 */
@Composable
internal fun OneHandedPresentation(
    enabled: Boolean,
    placement: OneHandedModePlacement,
    forceNormalLayout: Boolean,
    scaleMultiplier: Float = 1f,
    onScaleMultiplierChange: (Float) -> Unit = {},
    onPlacementToggle: () -> Unit,
    placementToggleDescription: String,
    onExit: () -> Unit = {},
    exitDescription: String = "Exit one-handed mode",
    resizeHandleDescription: String = "Resize one-handed mode",
    content: @Composable (Modifier) -> Unit,
) {
    val reduceContent = enabled && !forceNormalLayout
    // Reuse a restrained no-bounce spring across related properties. Compose springs retain
    // velocity when targets change mid-flight, keeping rapid side switches continuous.
    val floatAnimationSpec = spring<Float>(
        dampingRatio = OneHandedSpringDamping,
        stiffness = OneHandedSpringStiffness,
    )
    val dpAnimationSpec = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = OneHandedSpringDamping,
        stiffness = OneHandedSpringStiffness,
    )
    val backdropAlpha by animateFloatAsState(
        targetValue = if (reduceContent) 1f else 0f,
        animationSpec = floatAnimationSpec,
        label = "one_handed_backdrop",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (reduceContent) 28.dp else 0.dp,
        animationSpec = dpAnimationSpec,
        label = "one_handed_corner_radius",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (reduceContent) 18.dp else 0.dp,
        animationSpec = dpAnimationSpec,
        label = "one_handed_shadow",
    )
    val targetPivot = if (placement == OneHandedModePlacement.Left) 0f else 1f
    val pivotFraction by animateFloatAsState(
        targetValue = targetPivot,
        animationSpec = floatAnimationSpec,
        label = "one_handed_horizontal_anchor",
    )
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val scrim = MaterialTheme.colorScheme.scrim
    val backgroundBrush = remember(background, surface, surfaceVariant) {
        Brush.verticalGradient(
            colors = listOf(
                surface.copy(alpha = 0.94f),
                surfaceVariant.copy(alpha = 0.96f),
                background.copy(alpha = 0.98f),
            ),
        )
    }
    val currentCornerRadiusPx = rememberUpdatedState(with(LocalDensity.current) { cornerRadius.toPx() })
    val currentScaleMultiplier = rememberUpdatedState(scaleMultiplier)
    val currentOnScaleMultiplierChange = rememberUpdatedState(onScaleMultiplierChange)
    val density = LocalDensity.current
    val currentPivotFraction = rememberUpdatedState(pivotFraction)
    val surfaceShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    // The backdrop records the existing content layer, not a second active app composition.
    // It is only retained while the reduced presentation is visible and is never used for playback.
    val backdropSource = if (reduceContent) rememberNuvioBackdrop() else null
    val liveBackdrop = remember(backdropSource) {
        backdropSource?.let(::OneHandedLiveBackdrop)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val currentViewportHeightPx = rememberUpdatedState(viewportHeightPx)
        val targetScale = if (forceNormalLayout) {
            1f
        } else {
            oneHandedScaleForViewport(maxWidth.value, maxHeight.value, scaleMultiplier)
        }
        val scale by animateFloatAsState(
            targetValue = if (reduceContent) targetScale else 1f,
            animationSpec = floatAnimationSpec,
            label = "one_handed_scale",
        )
        val currentScale = rememberUpdatedState(scale)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, forceNormalLayout) {
                    awaitEachGesture {
                        if (forceNormalLayout) return@awaitEachGesture
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val gestureScale = currentScale.value
                        if (!enabled && gestureScale >= 0.999f) return@awaitEachGesture

                        // Leave the exit and side-switch targets for their child click handlers.
                        val controlGroupWidth = 104.dp.toPx()
                        val controlTop = 12.dp.toPx()
                        if (enabled && down.position.x in ((size.width - controlGroupWidth) / 2f)..((size.width + controlGroupWidth) / 2f) &&
                            down.position.y in controlTop..(controlTop + 48.dp.toPx())
                        ) return@awaitEachGesture

                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val horizontalOffset = oneHandedHorizontalOffsetPx(
                            widthPx = width,
                            scale = gestureScale,
                            pivotFraction = currentPivotFraction.value,
                        )
                        val reducedHeight = height * gestureScale
                        val top = height - reducedHeight
                        val resizeHandleCenterX = width * (
                            currentPivotFraction.value * (1f - gestureScale) + gestureScale / 2f
                        )
                        val resizeHandleHalfWidth = 44.dp.toPx()
                        val resizeHandleHalfHeight = 22.dp.toPx()
                        if (enabled && down.position.x in (resizeHandleCenterX - resizeHandleHalfWidth)..(resizeHandleCenterX + resizeHandleHalfWidth) &&
                            down.position.y in (top - resizeHandleHalfHeight)..(top + resizeHandleHalfHeight)
                        ) return@awaitEachGesture
                        val mappedX = (down.position.x - horizontalOffset) / gestureScale
                        val mappedY = (down.position.y - top) / gestureScale
                        val insideReducedSurface = isWithinRoundedRect(
                            x = mappedX,
                            y = mappedY,
                            width = width,
                            height = height,
                            cornerRadius = currentCornerRadiusPx.value,
                        )

                        if (!insideReducedSurface) {
                            // The outside region is deliberately inert: keep the gesture consumed
                            // so hidden app content cannot activate, but never exit one-handed mode.
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (reduceContent && liveBackdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = liveBackdrop,
                                shape = { RectangleShape },
                                effects = { blur(18.dp.toPx()) },
                                onDrawSurface = { drawRect(surface.copy(alpha = 0.08f)) },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .drawBehind {
                        if (!forceNormalLayout && backdropAlpha > 0f) {
                            // Keep a small theme tint under the live copy to avoid a blank first frame.
                            drawRect(
                                brush = backgroundBrush,
                                alpha = backdropAlpha * if (liveBackdrop != null) 0.12f else 1f,
                            )
                            drawRect(color = scrim.copy(alpha = 0.24f * backdropAlpha))
                        }
                    },
            )

            val contentModifier = if (forceNormalLayout) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(
                            pivotFractionX = pivotFraction,
                            pivotFractionY = 1f,
                        )
                        shape = surfaceShape
                        clip = true
                        this.shadowElevation = shadowElevation.toPx()
                    }
                    // Keep capture inside the transform layer so the recorded layer stays at
                    // normal viewport size while the visible content is scaled.
                    .then(
                        if (reduceContent && backdropSource != null) {
                            Modifier.nuvioBackdropSource(backdropSource)
                        } else {
                            Modifier
                        },
                    )
            }
            content(contentModifier)

            if (reduceContent) {
                // This handle floats over the top edge of the transformed surface. Keep gesture
                // keys stable while its position follows scale changes, so a resize drag continues.
                val handleX = maxWidth * (
                    pivotFraction * (1f - scale) + scale / 2f
                ) - 44.dp
                val handleY = maxHeight * (1f - scale) - 20.dp
                Box(
                    modifier = Modifier
                        .offset(x = handleX, y = handleY)
                        .size(width = 88.dp, height = 40.dp)
                        .background(
                            color = surfaceVariant.copy(alpha = 0.96f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .pointerInput(enabled, forceNormalLayout) {
                            if (enabled && !forceNormalLayout) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        val availableHeightPx = currentViewportHeightPx.value
                                        val nextMultiplier = (
                                            currentScaleMultiplier.value - dragAmount / availableHeightPx
                                        ).coerceIn(
                                            OneHandedMinimumScaleMultiplier,
                                            OneHandedMaximumScaleMultiplier,
                                        )
                                        currentOnScaleMultiplierChange.value(nextMultiplier)
                                    },
                                )
                            }
                        }
                        .semantics {
                            contentDescription = resizeHandleDescription
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) {
                            Box(
                                Modifier
                                    .size(width = 3.dp, height = 12.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = reduceContent,
                enter = fadeIn(animationSpec = floatAnimationSpec),
                exit = fadeOut(animationSpec = floatAnimationSpec),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = surfaceVariant.copy(alpha = 0.96f),
                                shape = CircleShape,
                            )
                            .clickable(onClick = onExit)
                            .semantics {
                                contentDescription = exitDescription
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = surfaceVariant.copy(alpha = 0.96f),
                                shape = CircleShape,
                            )
                            .clickable(onClick = onPlacementToggle)
                            .semantics {
                                contentDescription = placementToggleDescription
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↔",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps a little more of the original size on compact phones to protect touch usability, while
 * capping the reduced surface on larger viewports. On very large screens the surface may exceed
 * the preferred footprint rather than making every control excessively small. Scaling still
 * reduces effective touch-target size, so device testing remains necessary.
 */
internal fun oneHandedScaleForViewport(
    widthDp: Float,
    heightDp: Float,
    scaleMultiplier: Float = 1f,
): Float {
    val multiplier = normalizedOneHandedScaleMultiplier(scaleMultiplier)
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) {
        return (OneHandedScale * multiplier).coerceIn(
            OneHandedMinimumViewportScale,
            OneHandedMaximumViewportScale,
        )
    }
    val preferredScale = if (
        widthDp <= OneHandedCompactWidthDp || heightDp <= OneHandedCompactHeightDp
    ) {
        OneHandedCompactScale
    } else {
        OneHandedScale
    }
    val baseScale = minOf(
        preferredScale,
        OneHandedMaxWidthDp / widthDp,
        OneHandedMaxHeightDp / heightDp,
    ).coerceIn(OneHandedMinimumScale, preferredScale)
    val viewportMaximumScale = minOf(
        OneHandedMaximumViewportScale,
        OneHandedMaxWidthDp / widthDp,
        OneHandedMaxHeightDp / heightDp,
    ).coerceAtLeast(OneHandedMinimumViewportScale)
    return (baseScale * multiplier).coerceIn(
        OneHandedMinimumViewportScale,
        viewportMaximumScale,
    )
}

/** Computes the scaled surface's horizontal edge for the same animated pivot used by graphicsLayer. */
internal fun oneHandedHorizontalOffsetPx(
    widthPx: Float,
    scale: Float,
    pivotFraction: Float,
): Float {
    if (!widthPx.isFinite() || !scale.isFinite() || !pivotFraction.isFinite()) return 0f
    return widthPx * (1f - scale.coerceIn(0f, 1f)) * pivotFraction.coerceIn(0f, 1f)
}

/** Hit-tests the same rounded rectangle that clips the scaled app surface. */
internal fun isWithinRoundedRect(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
): Boolean {
    if (x < 0f || y < 0f || x > width || y > height) return false
    val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
    if (radius == 0f || (x >= radius && x <= width - radius) || (y >= radius && y <= height - radius)) {
        return true
    }
    val centerX = if (x < radius) radius else width - radius
    val centerY = if (y < radius) radius else height - radius
    val dx = x - centerX
    val dy = y - centerY
    return dx * dx + dy * dy <= radius * radius
}
