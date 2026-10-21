package com.nuvio.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ephemeral presentation state for the in-app one-handed UI.
 *
 * The immutable state is shared by every tab composition, especially on iOS where each native
 * tab owns a separate Compose root. It remains in memory only and is never persisted.
 */
internal data class OneHandedModeState(
    val enabled: Boolean = false,
    val placement: OneHandedModePlacement = OneHandedModePlacement.Right,
) {
    fun toggle(): OneHandedModeState = copy(enabled = !enabled)

    fun dismissOutside(): OneHandedModeState = copy(enabled = false)

    fun disableForPlayerEntry(): OneHandedModeState = copy(enabled = false)
}

internal enum class OneHandedModePlacement {
    Left,
    Right,
}

/** One process-local source of truth shared by Android and all iOS tab compositions. */
internal class OneHandedModeController(
    initialState: OneHandedModeState = OneHandedModeState(),
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<OneHandedModeState> = mutableState.asStateFlow()

    fun toggle() {
        mutableState.update(OneHandedModeState::toggle)
    }

    fun dismissOutside() {
        mutableState.update(OneHandedModeState::dismissOutside)
    }

    fun disableForPlayerEntry() {
        mutableState.update(OneHandedModeState::disableForPlayerEntry)
    }
}

internal val appOneHandedModeController = OneHandedModeController()
