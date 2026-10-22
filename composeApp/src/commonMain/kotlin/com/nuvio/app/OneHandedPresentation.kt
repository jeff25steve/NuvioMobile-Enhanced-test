package com.nuvio.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import com.nuvio.app.core.ui.glass.isLiquidGlassAvailable

private const val OneHandedScale = 0.78f
private const val OneHandedMaxWidthDp = 600f
private const val OneHandedMaxHeightDp = 760f
private const val OneHandedMinimumScale = 0.60f
internal const val OneHandedMinimumViewportScale = 0.42f
internal const val OneHandedMaximumViewportScale = 0.86f
private const val OneHandedCompactScale = 0.78f
private const val OneHandedCompactWidthDp = 360f
private const val OneHandedCompactHeightDp = 640f

private data class OneHandedDragPreview(
    val scaleMultiplier: Float,
    val placement: OneHandedModePlacement,
)

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
 * When the renderer supports it, an offscreen GraphicsLayer records the already-composed app
 * content at normal size and renders it behind the reduced UI with blur and dimming. This is a
 * live render layer, not a second composition or persisted screenshot. It is only active while
 * the reduced app is visible; playback and unsupported rendering targets use the theme fallback.
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
        targetValue = if (reduceContent) 16.dp else 0.dp,
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
    val currentOnPlacementToggle = rememberUpdatedState(onPlacementToggle)
    val density = LocalDensity.current
    val currentPivotFraction = rememberUpdatedState(pivotFraction)
    val currentPlacement = rememberUpdatedState(placement)
    val surfaceShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    // The backdrop records the existing content layer, not a second active app composition.
    // It is only retained while the reduced presentation is visible and is never used for playback.
    val backdropSource = if (reduceContent && isLiquidGlassAvailable()) rememberNuvioBackdrop() else null
    val liveBackdrop = remember(backdropSource) {
        backdropSource?.let(::OneHandedLiveBackdrop)
    }
    var dragPreview by remember { mutableStateOf<OneHandedDragPreview?>(null) }

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

                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val horizontalOffset = oneHandedHorizontalOffsetPx(
                            widthPx = width,
                            scale = gestureScale,
                            pivotFraction = currentPivotFraction.value,
                        )
                        val reducedHeight = height * gestureScale
                        val top = height - reducedHeight

                        // Keep the thin top drag target, and keep the side arrow
                        // interactive even though both touch targets sit outside the reduced app.
                        val handleCenterX = width * (
                            currentPivotFraction.value * (1f - gestureScale) + gestureScale / 2f
                        )
                        val handleHalfWidth = 28.dp.toPx()
                        if (enabled &&
                            down.position.x in (handleCenterX - handleHalfWidth)..(handleCenterX + handleHalfWidth) &&
                            down.position.y in (top - 25.dp.toPx())..(top + 15.dp.toPx())
                        ) return@awaitEachGesture

                        val sideSwitchCenterX = if (currentPlacement.value == OneHandedModePlacement.Right) {
                            width * (1f - gestureScale) - 24.dp.toPx()
                        } else {
                            width * gestureScale + 24.dp.toPx()
                        }
                        val sideSwitchCenterY = top + reducedHeight / 2f
                        if (enabled &&
                            down.position.x in (sideSwitchCenterX - 20.dp.toPx())..(sideSwitchCenterX + 20.dp.toPx()) &&
                            down.position.y in (sideSwitchCenterY - 28.dp.toPx())..(sideSwitchCenterY + 28.dp.toPx())
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
                        if (liveBackdrop != null) {
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

            // Keep the live app untouched while the user positions/resizes it. This transient
            // outline previews the target bounds under the drag and disappears when released.
            if (reduceContent) {
                dragPreview?.let { preview ->
                    val previewScale = oneHandedScaleForViewport(
                        widthDp = maxWidth.value,
                        heightDp = maxHeight.value,
                        scaleMultiplier = preview.scaleMultiplier,
                    )
                    val previewPivot = if (preview.placement == OneHandedModePlacement.Left) 0f else 1f
                    val previewLeft = maxWidth * (1f - previewScale) * previewPivot
                    val previewTop = maxHeight * (1f - previewScale)
                    Box(
                        modifier = Modifier
                            .offset(x = previewLeft, y = previewTop)
                            .size(
                                width = maxWidth * previewScale,
                                height = maxHeight * previewScale,
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.84f),
                                shape = RoundedCornerShape(16.dp),
                            ),
                    )
                }
            }

            if (reduceContent) {
                // Match the reference's grip interaction: the visible pill is tiny, but its
                // transparent target is generous. Vertical movement continuously resizes the
                // bottom-anchored surface; a deliberate horizontal drag snaps it to the other side.
                // A tap alone is inert, so this control never accidentally exits the mode.
                val handleCenterX = maxWidth * (
                    pivotFraction * (1f - scale) + scale / 2f
                )
                val handleTop = maxHeight * (1f - scale) - 25.dp
                Box(
                    modifier = Modifier
                        .offset(x = handleCenterX - 28.dp, y = handleTop)
                        .size(width = 56.dp, height = 40.dp)
                        .pointerInput(enabled, forceNormalLayout) {
                            if (enabled && !forceNormalLayout) {
                                var originalPlacement = currentPlacement.value
                                var draggedPlacement = originalPlacement
                                var totalHorizontalDragPx = 0f
                                var draggedScaleMultiplier = currentScaleMultiplier.value
                                val sideSwitchThresholdPx = with(density) { 48.dp.toPx() }
                                detectDragGestures(
                                    onDragStart = {
                                        originalPlacement = currentPlacement.value
                                        draggedPlacement = originalPlacement
                                        totalHorizontalDragPx = 0f
                                        draggedScaleMultiplier = currentScaleMultiplier.value
                                        dragPreview = OneHandedDragPreview(
                                            scaleMultiplier = draggedScaleMultiplier,
                                            placement = draggedPlacement,
                                        )
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val availableHeightPx =
                                            currentViewportHeightPx.value.coerceAtLeast(1f)
                                        draggedScaleMultiplier = (
                                            draggedScaleMultiplier - dragAmount.y / availableHeightPx
                                        ).coerceIn(
                                            OneHandedMinimumScaleMultiplier,
                                            OneHandedMaximumScaleMultiplier,
                                        )

                                        totalHorizontalDragPx += dragAmount.x
                                        draggedPlacement = when {
                                            totalHorizontalDragPx <= -sideSwitchThresholdPx ->
                                                OneHandedModePlacement.Left
                                            totalHorizontalDragPx >= sideSwitchThresholdPx ->
                                                OneHandedModePlacement.Right
                                            else -> originalPlacement
                                        }
                                        dragPreview = OneHandedDragPreview(
                                            scaleMultiplier = draggedScaleMultiplier,
                                            placement = draggedPlacement,
                                        )
                                    },
                                    onDragEnd = {
                                        currentOnScaleMultiplierChange.value(draggedScaleMultiplier)
                                        if (draggedPlacement != currentPlacement.value) {
                                            currentOnPlacementToggle.value()
                                        }
                                        dragPreview = null
                                    },
                                    onDragCancel = {
                                        dragPreview = null
                                    },
                                )
                            }
                        }
                        .semantics {
                            contentDescription = "Move or resize one-handed mode"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 3.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.88f),
                                shape = RoundedCornerShape(3.dp),
                            ),
                    )
                }

                // Samsung-inspired side arrow, placed just outside the reduced surface's
                // vertical midpoint. Direction always indicates the side the surface can move to.
                val sideSwitchCenterX = if (placement == OneHandedModePlacement.Right) {
                    maxWidth * (1f - scale) - 24.dp
                } else {
                    maxWidth * scale + 24.dp
                }
                val sideSwitchCenterY = maxHeight * (1f - scale / 2f)
                Box(
                    modifier = Modifier
                        .offset(x = sideSwitchCenterX - 20.dp, y = sideSwitchCenterY - 28.dp)
                        .size(width = 40.dp, height = 56.dp)
                        .clickable(onClick = onPlacementToggle)
                        .semantics {
                            contentDescription = placementToggleDescription
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (placement == OneHandedModePlacement.Right) "◀" else "▶",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

/**
 * Keeps a little more of the original size on compact phones while respecting usable viewport
 * bounds. The user-controlled multiplier is clamped independently of the viewport cap. Scaling
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
