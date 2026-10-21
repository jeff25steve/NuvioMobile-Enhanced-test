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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private const val OneHandedScale = 0.66f
private const val OneHandedMaxWidthDp = 600f
private const val OneHandedMaxHeightDp = 760f
private const val OneHandedMinimumScale = 0.60f
private const val OneHandedCompactScale = 0.72f
private const val OneHandedCompactWidthDp = 360f
private const val OneHandedCompactHeightDp = 640f

private const val OneHandedSpringDamping = Spring.DampingRatioNoBouncy
private const val OneHandedSpringStiffness = Spring.StiffnessMedium

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
    onOutsideTap: () -> Unit,
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
    val currentOnOutsideTap = rememberUpdatedState(onOutsideTap)
    val currentCornerRadiusPx = rememberUpdatedState(with(LocalDensity.current) { cornerRadius.toPx() })
    val currentPivotFraction = rememberUpdatedState(pivotFraction)
    val surfaceShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val targetScale = if (forceNormalLayout) {
            1f
        } else {
            oneHandedScaleForViewport(maxWidth.value, maxHeight.value)
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

                        // Leave the dedicated side-switch target unconsumed so its clickable child
                        // receives the gesture instead of the outside-tap dismiss handler.
                        val switchTargetSize = 48.dp.toPx()
                        val switchTop = 12.dp.toPx()
                        if (enabled && down.position.x in ((size.width - switchTargetSize) / 2f)..((size.width + switchTargetSize) / 2f) &&
                            down.position.y in switchTop..(switchTop + switchTargetSize)
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
                            down.consume()
                            var tapCandidate = true
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } > 1) {
                                    tapCandidate = false
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    tapCandidate = false
                                } else if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    tapCandidate = false
                                }
                                event.changes.forEach { it.consume() }
                                if (change != null && !change.pressed) {
                                    if (tapCandidate) currentOnOutsideTap.value()
                                    break
                                }
                            }
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (!forceNormalLayout && backdropAlpha > 0f) {
                            drawRect(brush = backgroundBrush, alpha = backdropAlpha)
                            drawRect(color = scrim.copy(alpha = 0.22f * backdropAlpha))
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
            }
            content(contentModifier)

            AnimatedVisibility(
                visible = reduceContent,
                enter = fadeIn(animationSpec = floatAnimationSpec),
                exit = fadeOut(animationSpec = floatAnimationSpec),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
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

/**
 * Keeps a little more of the original size on compact phones to protect touch usability, while
 * capping the reduced surface on larger viewports. On very large screens the surface may exceed
 * the preferred footprint rather than making every control excessively small. Scaling still
 * reduces effective touch-target size, so device testing remains necessary.
 */
internal fun oneHandedScaleForViewport(widthDp: Float, heightDp: Float): Float {
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) {
        return OneHandedScale
    }
    val preferredScale = if (
        widthDp <= OneHandedCompactWidthDp || heightDp <= OneHandedCompactHeightDp
    ) {
        OneHandedCompactScale
    } else {
        OneHandedScale
    }
    return minOf(
        preferredScale,
        OneHandedMaxWidthDp / widthDp,
        OneHandedMaxHeightDp / heightDp,
    ).coerceIn(OneHandedMinimumScale, preferredScale)
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
