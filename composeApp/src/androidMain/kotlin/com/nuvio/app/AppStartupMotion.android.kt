package com.nuvio.app

import android.animation.ValueAnimator
import android.os.Build

/**
 * Android exposes the system-wide animator state from API 26.
 * On older supported versions, keep the app's existing motion rather than
 * reading hidden/system settings.
 */
internal actual fun platformPrefersReducedMotion(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()
