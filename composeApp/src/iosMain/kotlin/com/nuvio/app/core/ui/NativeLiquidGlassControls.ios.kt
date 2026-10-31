package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISegmentedControl
import platform.UIKit.UISwitch

@OptIn(ExperimentalForeignApi::class)
internal actual fun isNativeLiquidGlassControlsAvailable(): Boolean {
    val majorVersion = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        majorVersion.toLong()
    }
    return shouldUseNativeLiquidGlassControls(majorVersion)
}

@Composable
internal actual fun NuvioNativeLiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    UIKitView(
        factory = {
            LiquidGlassNativeSwitch().apply {
                setOn(checked, animated = false)
            }
        },
        modifier = modifier.size(width = 51.dp, height = 31.dp),
        update = { nativeSwitch ->
            nativeSwitch.valueChangedListener = onCheckedChange
            if (nativeSwitch.on != checked) {
                nativeSwitch.setOn(checked, animated = false)
            }
        },
    )
}

@Composable
internal actual fun NuvioNativeLiquidGlassSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelectionChanged: (Int) -> Unit,
    modifier: Modifier,
) {
    UIKitView(
        factory = {
            LiquidGlassNativeSegmentedControl(items).apply {
                setSelectedSegmentIndex(selectedIndex.toLong())
            }
        },
        modifier = modifier.height(32.dp),
        update = { nativeControl ->
            nativeControl.updateItems(items)
            nativeControl.selectionChangedListener = onSelectionChanged
            if (nativeControl.selectedSegmentIndex.toInt() != selectedIndex) {
                nativeControl.setSelectedSegmentIndex(selectedIndex.toLong())
            }
        },
    )
}

private class LiquidGlassNativeSwitch : UISwitch(frame = CGRectZero) {
    var valueChangedListener: ((Boolean) -> Unit)? = null

    init {
        addTarget(
            target = this,
            action = NSSelectorFromString("handleValueChanged"),
            forControlEvents = UIControlEventValueChanged,
        )
    }

    @ObjCAction
    fun handleValueChanged() {
        valueChangedListener?.invoke(on)
    }
}

private class LiquidGlassNativeSegmentedControl(
    initialItems: List<String>,
) : UISegmentedControl(frame = CGRectZero) {
    private var itemTitles: List<String> = emptyList()
    var selectionChangedListener: ((Int) -> Unit)? = null

    init {
        updateItems(initialItems)
        addTarget(
            target = this,
            action = NSSelectorFromString("handleValueChanged"),
            forControlEvents = UIControlEventValueChanged,
        )
    }

    fun updateItems(items: List<String>) {
        if (itemTitles == items) return
        removeAllSegments()
        items.forEachIndexed { index, title ->
            insertSegmentWithTitle(
                title = title,
                atIndex = index.toULong(),
                animated = false,
            )
        }
        itemTitles = items.toList()
    }

    @ObjCAction
    fun handleValueChanged() {
        selectionChangedListener?.invoke(selectedSegmentIndex.toInt())
    }
}
