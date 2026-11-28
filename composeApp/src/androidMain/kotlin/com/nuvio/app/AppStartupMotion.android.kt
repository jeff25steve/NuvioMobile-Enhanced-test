package com.nuvio.app

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Prefer the platform's complete animator state on API 26+ (including Battery Saver).
 * API 24–25 use the public, read-only animator scale setting. If it cannot be read,
 * preserve the app's normal animation rather than guessing or changing a system setting.
 */
@Composable
internal actual fun rememberPlatformPrefersReducedMotion(): Boolean {
    val applicationContext = LocalContext.current.applicationContext
    return remember(applicationContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            !ValueAnimator.areAnimatorsEnabled()
        } else {
            runCatching {
                Settings.Global.getFloat(
                    applicationContext.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) <= 0f
            }.getOrDefault(false)
        }
    }
}
