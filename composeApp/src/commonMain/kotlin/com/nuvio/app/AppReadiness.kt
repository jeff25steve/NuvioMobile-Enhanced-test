package com.nuvio.app

internal fun shouldShowMainLaunchOverlay(
    ownsAppRuntime: Boolean,
    showLaunchOverlay: Boolean,
    rootContentReady: Boolean,
    fallbackElapsed: Boolean,
): Boolean = ownsAppRuntime && showLaunchOverlay && !rootContentReady && !fallbackElapsed

internal fun shouldReportMainContentReady(
    rootContentReady: Boolean,
    overlayIdle: Boolean,
    overlayVisible: Boolean,
): Boolean = rootContentReady && overlayIdle && !overlayVisible
