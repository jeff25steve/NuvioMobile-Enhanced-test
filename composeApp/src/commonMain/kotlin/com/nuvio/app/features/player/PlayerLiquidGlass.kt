package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.ui.glass.LiquidGlassBackdropHost
import com.nuvio.app.core.ui.glass.LiquidGlassSettings
import com.nuvio.app.core.ui.glass.LiquidGlassMaterial
import com.nuvio.app.core.ui.glass.LocalLiquidGlassPreviewOverrides
import com.nuvio.app.core.ui.glass.LocalLiquidGlassPreviewSettings
import com.nuvio.app.core.ui.glass.LiquidGlassSurface
import com.nuvio.app.core.ui.glass.LiquidGlassStyle
import com.nuvio.app.core.ui.glass.ambientLiquidGlass
import com.nuvio.app.core.ui.glass.effectiveLiquidGlassMaterial
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
import com.nuvio.app.core.ui.glass.shouldRenderLiquidGlassSurface
import com.nuvio.app.core.ui.glass.liquidGlassShape
import kotlin.math.roundToInt

internal val PlayerGlassTint = Color(0xFF101014).copy(alpha = 0.52f)

// ---------------------------------------------------------------------------------------------
// Player liquid glass: clear glass with a light blur over the live video.
// ---------------------------------------------------------------------------------------------

/** Light blur so the video stays readable through the glass. */
internal val PlayerGlassBlur: Dp = 6.dp

/** Near-clear: a faint white sheen instead of a dark fill. */
internal val PlayerGlassSurfaceTint = Color.White.copy(alpha = 0.05f)

/**
 * How strongly the blurred video copy is drawn inside the glass. Below 1 the live video shows through,
 * so the glass stays translucent (and never turns dark if a captured frame comes back black).
 */
internal const val PlayerGlassBackdropOpacity = 0.55f

/** Used when there is no live backdrop (Android < 12, protected/DRM video, glass turned off). */
internal val PlayerGlassFallback = Color.White.copy(alpha = 0.08f)

/** Hairline rim that sells the glass edge on top of Kyant's highlight. */
internal val PlayerGlassRim = Color.White.copy(alpha = 0.22f)

/**
 * The one glass recipe every player control uses: Kyant backdrop with vibrancy, a light blur, lens
 * refraction and edge highlight, sampled from the live video frame provided by
 * [rememberVideoFrameBackdrop]. Falls back to a translucent fill when the backdrop isn't available.
 */
@Composable
internal fun Modifier.playerLiquidGlass(
    shape: Shape,
    blurRadius: Dp = PlayerGlassBlur,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 12.dp,
    tint: Color = PlayerGlassSurfaceTint,
    fallback: Color = PlayerGlassFallback,
    backdropOpacity: Float = PlayerGlassBackdropOpacity,
    rim: Boolean = true,
): Modifier {
    val previewOverrides = LocalLiquidGlassPreviewSettings.current?.forSurface(LiquidGlassSurface.PLAYER)
        ?: LocalLiquidGlassPreviewOverrides.current
    val overrides = previewOverrides ?: LiquidGlassSettings.surfaceOverrides.forSurface(LiquidGlassSurface.PLAYER)
    val materialStyle = LiquidGlassStyle(
        material = effectiveLiquidGlassMaterial(
            surface = LiquidGlassSurface.PLAYER,
            material = overrides.material,
            refractionSupported = isLiquidRefractionSupported(),
            cornerRadiusPercent = overrides.cornerRadiusPercent,
        ),
    )
    val enabled = shouldRenderLiquidGlassSurface(
        surface = LiquidGlassSurface.PLAYER,
        overrides = overrides,
        preview = previewOverrides != null,
        masterEnabled = LiquidGlassSettings.enabled,
    ) && materialStyle.usesBackdrop
    val effectiveShape = if (enabled) {
        liquidGlassShape(
            surface = LiquidGlassSurface.PLAYER,
            defaultShape = shape,
            preview = previewOverrides != null,
        )
    } else {
        shape
    }
    val effectiveTint = if (enabled) overrides.tintOpacity?.let { tint.copy(alpha = it) } ?: tint else tint
    val glass = if (!enabled) {
        // The master switch and this area's switch both restore the original fallback appearance.
        clip(shape).background(fallback, shape)
    } else {
        clip(effectiveShape).ambientLiquidGlass(
            shape = effectiveShape,
            tint = effectiveTint,
            fallback = fallback,
            blurRadius = if (materialStyle.usesBlur) overrides.blurRadiusDp?.dp ?: blurRadius else 0.dp,
            refractionHeight = if (materialStyle.usesLens) overrides.refractionHeightDp?.dp ?: refractionHeight else 0.dp,
            refractionAmount = if (materialStyle.usesLens) overrides.refractionAmountDp?.dp ?: refractionAmount else 0.dp,
            withVibrancy = overrides.withVibrancy ?: true,
            withHighlight = overrides.withHighlight ?: true,
            withShadow = overrides.withShadow ?: false,
            backdropOpacity = backdropOpacity,
            chromaticAberration = overrides.chromaticAberration ?: false,
            depthEffect = overrides.depthEffect ?: false,
            preview = previewOverrides != null,
        )
    }
    return if (rim) glass.border(0.75.dp, PlayerGlassRim, effectiveShape) else glass
}

// Keep the captured frame identical to the real picture so the glass reads as transparent (no dimming).
private val VideoFrameColorFilter: ColorFilter? = null

@Stable
class VideoFrameBackdrop : Backdrop, LiquidGlassBackdropHost {

    override val isCoordinatesDependent: Boolean = true

    private var frame: ImageBitmap? by mutableStateOf(null, neverEqualPolicy())

    private var frameBounds: Rect = Rect.Zero

    private var ready: Boolean by mutableStateOf(false)

    override val isReady: Boolean
        get() = ready

    internal var consumers by mutableIntStateOf(0)
        private set

    override fun acquire() {
        consumers += 1
    }

    override fun release() {
        consumers = (consumers - 1).coerceAtLeast(0)
    }

    internal fun publishFrame(image: ImageBitmap, boundsOnScreen: Rect) {
        frameBounds = boundsOnScreen
        frame = image
        if (!ready) ready = true
    }

    internal fun clearFrame() {
        frame = null
        frameBounds = Rect.Zero
        if (ready) ready = false
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val target = coordinates ?: return
        if (!target.isAttached) return
        val image = frame ?: return
        val bounds = frameBounds
        if (bounds.isEmpty) return
        val onScreen = target.positionOnScreen()
        val origin = if (onScreen.isSpecified) onScreen else target.positionInWindow()
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(
                (bounds.left - origin.x).roundToInt(),
                (bounds.top - origin.y).roundToInt(),
            ),
            dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
            colorFilter = VideoFrameColorFilter,
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
internal expect fun rememberVideoFrameBackdrop(enabled: Boolean): VideoFrameBackdrop?
