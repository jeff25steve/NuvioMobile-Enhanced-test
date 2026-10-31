package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS 26 system controls automatically adopt Apple's Liquid Glass interaction treatment. */
internal fun shouldUseNativeLiquidGlassControls(operatingSystemMajorVersion: Long): Boolean =
    operatingSystemMajorVersion >= 26L

internal expect fun isNativeLiquidGlassControlsAvailable(): Boolean

@Composable
internal expect fun NuvioNativeLiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
internal expect fun NuvioNativeLiquidGlassSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelectionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
)
