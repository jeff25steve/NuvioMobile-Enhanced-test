package com.nuvio.app.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported

fun isLiquidGlassAvailable(): Boolean = isRenderEffectSupported()

fun isLiquidRefractionSupported(): Boolean = isRuntimeShaderSupported()

internal interface LiquidGlassBackdropHost {
    val isReady: Boolean
    fun acquire() {}
    fun release() {}
}

internal val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Stable
internal class NuvioBackdrop internal constructor(
    internal val graphicsLayer: GraphicsLayer,
) : Backdrop, LiquidGlassBackdropHost {

    override val isCoordinatesDependent: Boolean = true

    internal var sourceCoordinates: LayoutCoordinates? by mutableStateOf(null)

    override val isReady: Boolean
        get() = sourceCoordinates != null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val target = coordinates ?: return
        val source = sourceCoordinates ?: return
        if (!target.isAttached || !source.isAttached) return
        val offset = offsetBetween(source, target)
        translate(-offset.x, -offset.y) {
            drawLayer(graphicsLayer)
        }
    }

    private fun offsetBetween(source: LayoutCoordinates, target: LayoutCoordinates): Offset {
        if (source.findRootCoordinates() === target.findRootCoordinates()) {
            try {
                return source.localPositionOf(target, Offset.Zero)
            } catch (_: Exception) {
            }
        }
        val targetOnScreen = target.positionOnScreen()
        val sourceOnScreen = source.positionOnScreen()
        return if (targetOnScreen.isSpecified && sourceOnScreen.isSpecified) {
            targetOnScreen - sourceOnScreen
        } else {
            target.positionInWindow() - source.positionInWindow()
        }
    }
}

@Composable
internal fun rememberNuvioBackdrop(): NuvioBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    return remember(graphicsLayer) { NuvioBackdrop(graphicsLayer) }
}

internal fun Modifier.nuvioBackdropSource(backdrop: NuvioBackdrop): Modifier =
    this then NuvioBackdropSourceElement(backdrop)

private class NuvioBackdropSourceElement(
    val backdrop: NuvioBackdrop,
) : ModifierNodeElement<NuvioBackdropSourceNode>() {

    override fun create(): NuvioBackdropSourceNode = NuvioBackdropSourceNode(backdrop)

    override fun update(node: NuvioBackdropSourceNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.sourceCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "nuvioBackdropSource"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NuvioBackdropSourceElement) return false
        return backdrop == other.backdrop
    }

    override fun hashCode(): Int = backdrop.hashCode()
}

private class NuvioBackdropSourceNode(
    var backdrop: NuvioBackdrop,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    override fun ContentDrawScope.draw() {
        drawContent()
        val nodeDensity = requireDensity()
        val contentScope = this
        backdrop.graphicsLayer.record {
            val previousDensity = drawContext.density
            drawContext.density = nodeDensity
            try {
                contentScope.drawContent()
            } finally {
                drawContext.density = previousDensity
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.sourceCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.sourceCoordinates = null
    }
}
