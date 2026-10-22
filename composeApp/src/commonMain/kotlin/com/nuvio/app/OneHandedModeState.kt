package com.nuvio.app

import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.PlayerRoute
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

    fun togglePlacement(): OneHandedModeState = copy(
        placement = if (placement == OneHandedModePlacement.Left) {
            OneHandedModePlacement.Right
        } else {
            OneHandedModePlacement.Left
        },
    )

    fun disableForPlayerEntry(): OneHandedModeState = copy(enabled = false)
}

internal enum class OneHandedModePlacement {
    Left,
    Right,
}

/**
 * Identifies presentation states that must not apply the one-handed transform.
 *
 * Keep this decision pure so player route, hosted-player, and transitional loading
 * guards can be covered without constructing the full app runtime in tests.
 */
internal fun isPlayerPresentationActive(
    currentRoute: AppRoute?,
    visiblePlayerEntries: Int,
    streamLandscapeLoadingVisible: Boolean,
): Boolean =
    currentRoute is PlayerRoute ||
        visiblePlayerEntries > 0 ||
        streamLandscapeLoadingVisible

/** One process-local source of truth shared by Android and all iOS tab compositions. */
internal class OneHandedModeController(
    initialState: OneHandedModeState = OneHandedModeState(),
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<OneHandedModeState> = mutableState.asStateFlow()

    fun toggle() {
        mutableState.update { current -> current.toggle() }
    }

    fun togglePlacement() {
        mutableState.update { current -> current.togglePlacement() }
    }

    fun disableForPlayerEntry() {
        mutableState.update { current -> current.disableForPlayerEntry() }
    }

    fun disableForPlayerPresentationIfActive(isActive: Boolean) {
        if (!isActive) return
        mutableState.update { current ->
            if (current.enabled) current.disableForPlayerEntry() else current
        }
    }
}

internal val appOneHandedModeController = OneHandedModeController()
