package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Uses UIKit's public Reduce Motion system preference without changing it. */
@Composable
internal actual fun rememberPlatformPrefersReducedMotion(): Boolean =
    remember { UIAccessibilityIsReduceMotionEnabled() }
