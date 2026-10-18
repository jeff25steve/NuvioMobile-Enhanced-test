package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.kyant.backdrop.Backdrop
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassEffectSettings
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.DrawableResource

internal class FloatingNavigationItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val drawable: DrawableResource? = null,
    val content: (@Composable (onClick: () -> Unit) -> Unit)? = null,
)

internal expect val floatingNavigationGlowSupported: Boolean

@Composable
internal expect fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    backdrop: Backdrop? = null,
    contentPadding: PaddingValues = floatingNavigationBarPadding(),
    compactSize: Boolean = false,
    glowEnabled: Boolean = true,
    liquidGlassEnabled: Boolean = false,
    liquidGlassBarEffects: LiquidGlassEffectSettings = LiquidGlassEffectDefaults.floatingBar,
    liquidGlassSelectionEffects: LiquidGlassEffectSettings = LiquidGlassEffectDefaults.selectionLens,
    defaultIconColorOverride: Color? = null,
)

@Composable
internal fun floatingNavigationBarPadding(): PaddingValues = PaddingValues(
    bottom = nuvioBottomNavigationBarInsets().asPaddingValues().calculateBottomPadding() +
        nuvioBottomNavigationExtraVerticalPadding + 8.dp,
)
