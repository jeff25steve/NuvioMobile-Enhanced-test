package com.nuvio.app.features.player

/** Returns a usable source frame rate, preserving fractional rates such as 23.976 fps. */
internal fun normalizedVideoFrameRate(frameRate: Float): Float? =
    frameRate.takeIf { it.isFinite() && it > 0f }
