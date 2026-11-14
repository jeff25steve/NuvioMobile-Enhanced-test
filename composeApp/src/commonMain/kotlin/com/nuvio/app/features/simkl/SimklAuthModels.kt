package com.nuvio.app.features.simkl

import kotlinx.serialization.Serializable

enum class SimklConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

enum class SimklAuthError {
    MISSING_CLIENT_ID,
    INVALID_CALLBACK,
    INVALID_CALLBACK_STATE,
    AUTHORIZATION_EXPIRED,
    TOKEN_EXCHANGE_FAILED,
    INVALID_TOKEN_RESPONSE,
    AUTHORIZATION_REVOKED,
}

data class SimklAuthUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val accountId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val pendingAuthorizationStartedAtEpochMs: Long? = null,
    val error: SimklAuthError? = null,
)

@Serializable
internal data class SimklStoredAuthState(
    val username: String? = null,
    val accountId: Long? = null,
    val hasFetchedUserSettings: Boolean = false,
    val settingsActivityWatermark: String? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val pendingAuthorizationState: String? = null,
    val pendingAuthorizationStartedAtEpochMs: Long? = null,
    /** V1 PIN polling interval returned by Simkl; null migrates to the documented 5-second default. */
    val pendingPinPollIntervalSeconds: Int? = null,
    /** Absolute V1 PIN expiry so reloads do not restart the authorization lifetime. */
    val pendingPinExpiresAtEpochMs: Long? = null,
    /** AUTH V2 device flow `verification_uri_complete` (safe to display; the user still approves). */
    val pendingVerificationUrl: String? = null,
    val authenticationMethod: String? = null,
) {
    val hasPendingAuthorization: Boolean
        get() = !pendingAuthorizationState.isNullOrBlank()
}

internal enum class SimklSettingsRefreshAction {
    NONE,
    RECORD_WATERMARK,
    FETCH,
}

internal fun simklSettingsRefreshAction(
    state: SimklStoredAuthState,
    activityWatermark: String?,
): SimklSettingsRefreshAction = when {
    activityWatermark.isNullOrBlank() -> SimklSettingsRefreshAction.NONE
    activityWatermark == state.settingsActivityWatermark -> SimklSettingsRefreshAction.NONE
    state.settingsActivityWatermark == null && state.hasFetchedUserSettings -> {
        SimklSettingsRefreshAction.RECORD_WATERMARK
    }
    else -> SimklSettingsRefreshAction.FETCH
}

internal sealed interface SimklAuthCallback {
    data class AuthorizationCode(
        val code: String,
        val state: String,
    ) : SimklAuthCallback

    data object Invalid : SimklAuthCallback
    data object NotSimkl : SimklAuthCallback
}

/** Builds a clean UI snapshot when switching to a profile loaded from persistent storage. */
internal fun simklAuthStateAfterProfileReload(
    storedState: SimklStoredAuthState,
    accessToken: String?,
    credentialsConfigured: Boolean,
): SimklAuthUiState {
    val authenticated = !accessToken.isNullOrBlank()
    return SimklAuthUiState(
        mode = when {
            authenticated -> SimklConnectionMode.CONNECTED
            storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
            else -> SimklConnectionMode.DISCONNECTED
        },
        credentialsConfigured = credentialsConfigured,
        isLoading = false,
        username = storedState.username,
        accountId = storedState.accountId,
        tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
        pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
        error = null,
    )
}

/** A PKCE verifier is valid only while its browser authorization state is persisted. */
internal fun shouldClearOrphanedSimklCodeVerifier(state: SimklStoredAuthState): Boolean =
    state.pendingAuthorizationState.isNullOrBlank()

internal fun isSimklAuthProfileIdentityCurrent(
    loadedProfileId: Int?,
    activeProfileId: Int,
): Boolean = loadedProfileId == activeProfileId

internal fun isSimklAuthProfileGenerationCurrent(
    expectedGeneration: Long,
    currentGeneration: Long,
    loadedProfileId: Int?,
    activeProfileId: Int,
): Boolean =
    expectedGeneration == currentGeneration && loadedProfileId == activeProfileId
