package com.nuvio.app

/**
 * Ephemeral presentation state for the in-app one-handed UI.
 *
 * This is intentionally a plain immutable value: the owning composition controls its lifetime
 * and decides when a transition is applied. It is never persisted.
 */
internal data class OneHandedModeState(
    val enabled: Boolean = false,
    val placement: OneHandedModePlacement = OneHandedModePlacement.Right,
) {
    fun toggle(): OneHandedModeState = copy(enabled = !enabled)

    fun dismissOutside(): OneHandedModeState = copy(enabled = false)

    fun disableForPlayerEntry(): OneHandedModeState = copy(enabled = false)

    fun withPlacement(newPlacement: OneHandedModePlacement): OneHandedModeState =
        copy(placement = newPlacement)
}

internal enum class OneHandedModePlacement {
    Left,
    Right,
}
