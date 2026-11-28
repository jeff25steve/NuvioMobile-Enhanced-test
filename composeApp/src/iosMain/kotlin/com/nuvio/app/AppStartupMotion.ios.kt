package com.nuvio.app

import platform.UIKit.UIAccessibility

/** Use the public UIKit accessibility preference; never override the user's system setting. */
internal actual fun platformPrefersReducedMotion(): Boolean =
    UIAccessibility.isReduceMotionEnabled
