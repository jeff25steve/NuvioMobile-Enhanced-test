package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfiguration

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(
        val userId: String,
        val email: String?,
        val isAnonymous: Boolean,
    ) : AuthState
}

/**
 * Identity metadata captured when an account-scoped remote operation begins.
 * This deliberately contains no access token or other credential material.
 */
internal data class AuthSessionIdentity(
    val sessionGeneration: Long,
    val userId: String,
    val isAnonymous: Boolean,
    val serverConfiguration: ServerConfiguration,
)

/**
 * Requires both Nuvio's account/backend snapshot and the current Supabase SDK session to agree.
 * This catches a replacement session even while the sessionStatus collector is still lagging.
 */
internal fun isAuthSessionIdentityCurrent(
    expected: AuthSessionIdentity,
    currentSessionGeneration: Long,
    currentUserId: String?,
    currentIsAnonymous: Boolean?,
    currentServerConfiguration: ServerConfiguration,
    currentSupabaseUserId: String?,
): Boolean =
    expected.sessionGeneration == currentSessionGeneration &&
        expected.userId.isNotBlank() &&
        expected.userId == currentUserId &&
        expected.isAnonymous == currentIsAnonymous &&
        expected.serverConfiguration == currentServerConfiguration &&
        (
            if (expected.isAnonymous) currentSupabaseUserId == null
            else currentSupabaseUserId == expected.userId
        )

val AuthState.isLoggedIn: Boolean
    get() = this is AuthState.Authenticated

val AuthState.userId: String?
    get() = (this as? AuthState.Authenticated)?.userId

val AuthState.isAnonymous: Boolean
    get() = (this as? AuthState.Authenticated)?.isAnonymous == true
