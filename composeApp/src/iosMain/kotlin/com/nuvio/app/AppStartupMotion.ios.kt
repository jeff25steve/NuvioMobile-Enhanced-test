package com.nuvio.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Uses UIKit's public Reduce Motion system preference without changing it. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformPrefersReducedMotion(): Boolean =
    UIAccessibilityIsReduceMotionEnabled()
