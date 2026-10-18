package com.nuvio.app.features.settings

/**
 * Emits a direction only when the settings list crosses the top boundary.
 * Repeated scroll updates do not produce extra preview bounce events.
 */
internal class NavigationPreviewScrollBounceGate {
    private var wasAwayFromTop = false

    fun onScrollabilityChanged(canScrollBackward: Boolean): Float? {
        if (canScrollBackward == wasAwayFromTop) return null
        wasAwayFromTop = canScrollBackward
        return if (canScrollBackward) 1f else -1f
    }
}
