package com.nuvio.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private const val OneHandedScale = 0.66f
private const val OneHandedAnimationMillis = 360

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
    content: @Composable (Modifier) -> Unit,
) {
    val reduceContent = enabled && !forceNormalLayout
    val animationSpec = tween<Float>(
        durationMillis = OneHandedAnimationMillis,
        easing = FastOutSlowInEasing,
    )
    val scale by animateFloatAsState(
        targetValue = if (reduceContent) OneHandedScale else 1f,
        animationSpec = animationSpec,
        label = "one_handed_scale",
    )
    val backdropAlpha by animateFloatAsState(
        targetValue = if (reduceContent) 1f else 0f,
        animationSpec = animationSpec,
        label = "one_handed_backdrop",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (reduceContent) 28.dp else 0.dp,
        animationSpec = tween(
            durationMillis = OneHandedAnimationMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "one_handed_corner_radius",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (reduceContent) 18.dp else 0.dp,
        animationSpec = tween(
            durationMillis = OneHandedAnimationMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "one_handed_shadow",
    )
    val currentScale = rememberUpdatedState(scale)
    val currentCornerRadiusPx = rememberUpdatedState(with(LocalDensity.current) { cornerRadius.toPx() })
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
    val surfaceShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(enabled, placement, forceNormalLayout) {
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
                    val reducedWidth = width * gestureScale
                    val reducedHeight = height * gestureScale
                    val left = if (placement == OneHandedModePlacement.Left) {
                        0f
                    } else {
                        width - reducedWidth
                    }
                    val top = height - reducedHeight
                    val mappedX = (down.position.x - left) / gestureScale
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
                                if (tapCandidate) onOutsideTap()
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
                        pivotFractionX = if (placement == OneHandedModePlacement.Left) 0f else 1f,
                        pivotFractionY = 1f,
                    )
                    shape = surfaceShape
                    clip = true
                    this.shadowElevation = shadowElevation.toPx()
                }
        }
        content(contentModifier)
    }
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
