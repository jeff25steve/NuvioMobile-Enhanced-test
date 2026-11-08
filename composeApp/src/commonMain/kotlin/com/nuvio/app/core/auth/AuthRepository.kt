package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.concurrency.runCatchingUnlessCancelled
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")
    private val sessionGeneration = AuthSessionGeneration()

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private var trustNextSessionWithoutValidation = false

    fun initialize() {
        if (initialized) return
        initialized = true

        val generation = sessionGeneration.begin()
        val client = SupabaseProvider.client
        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        sessionStatusJob = scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (!sessionGeneration.isCurrent(generation)) return@collect
                if (AuthStorage.loadAnonymousUserId() != null) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (
                            !validateRemoteSession(
                                userId = userId,
                                generation = generation,
                                retrieveUser = { client.auth.retrieveUserForCurrentSession(false) },
                                clearSession = { client.auth.clearSession() },
                            )
                        ) return@collect
                        sessionGeneration.runIfCurrent(generation) {
                            _state.value = AuthState.Authenticated(
                                userId = userId,
                                email = user?.email,
                                isAnonymous = false,
                            )
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        sessionGeneration.runIfCurrent(generation) {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null) {
                            sessionGeneration.runIfCurrent(generation) {
                                _state.value = AuthState.Loading
                            }
                        }
                    }
                    is SessionStatus.RefreshFailure -> {
                        sessionGeneration.runIfCurrent(generation) {
                            _state.value = AuthState.Unauthenticated
                        }
                    }
                }
            }
        }
    }

    private suspend fun validateRemoteSession(
        userId: String,
        generation: Long,
        retrieveUser: suspend () -> Unit,
        clearSession: suspend () -> Unit,
    ): Boolean {
        if (!sessionGeneration.isCurrent(generation)) return false
        if (userId.isBlank() || validatedRemoteUserId == userId) return true
        if (trustNextSessionWithoutValidation) {
            return sessionGeneration.runIfCurrent(generation) {
                trustNextSessionWithoutValidation = false
                validatedRemoteUserId = userId
            }
        }

        val validation = runCatchingUnlessCancelled {
            retrieveUser()
        }
        if (!sessionGeneration.isCurrent(generation)) return false

        return validation.fold(
            onSuccess = {
                sessionGeneration.runIfCurrent(generation) {
                    validatedRemoteUserId = userId
                }
            },
            onFailure = { error ->
                if (isInvalidRemoteSessionError(error)) {
                    log.w(error) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                    clearLocalSessionAfterRemoteInvalidation(
                        accountUserId = userId,
                        clearSession = clearSession,
                        shouldContinue = { sessionGeneration.isCurrent(generation) },
                    )
                    false
                } else {
                    log.w(error) { "Unable to validate stored Supabase session; keeping cached auth state" }
                    sessionGeneration.isCurrent(generation)
                }
            },
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = Uuid.random().toString()
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        val client = SupabaseProvider.client
        val generation = sessionGeneration.current()
        return runCatchingUnlessCancelled {
            _error.value = null
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            currentCoroutineContext().ensureActive()
            val trusted = sessionGeneration.runIfCurrent(generation) {
                trustNextSessionWithoutValidation = true
            }
            if (!trusted) throw CancellationException("Authentication backend changed during sign-up")
            Unit
        }.onFailure { error ->
            log.e(error) { "Email sign-up failed" }
            _error.value = error.safeAuthErrorDescription()
                ?: getString(Res.string.auth_sign_up_failed)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        val client = SupabaseProvider.client
        val generation = sessionGeneration.current()
        return runCatchingUnlessCancelled {
            _error.value = null
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            currentCoroutineContext().ensureActive()
            val trusted = sessionGeneration.runIfCurrent(generation) {
                trustNextSessionWithoutValidation = true
            }
            if (!trusted) throw CancellationException("Authentication backend changed during sign-in")
            Unit
        }.onFailure { error ->
            log.e(error) { "Email sign-in failed" }
            _error.value = error.safeAuthErrorDescription()
                ?: getString(Res.string.auth_sign_in_failed)
        }
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        val accountUserId = (state.value as? AuthState.Authenticated)?.userId
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe(accountUserId) }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        sessionGeneration.invalidate()
        trustNextSessionWithoutValidation = false
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val sessionClear = runCatching { SupabaseProvider.client.auth.clearSession() }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        sessionGeneration.invalidate()
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!isInvalidRemoteSessionError(error)) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        val accountUserId = (state.value as? AuthState.Authenticated)?.userId
        val client = SupabaseProvider.client
        clearLocalSessionAfterRemoteInvalidation(
            accountUserId = accountUserId,
            clearSession = { client.auth.clearSession() },
        )
        return true
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation(
        accountUserId: String?,
        clearSession: suspend () -> Unit,
        shouldContinue: () -> Boolean = { true },
    ) {
        if (!shouldContinue()) return
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching {
            clearSession()
        }.onFailure { error ->
            log.w(error) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        if (!shouldContinue()) return
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe(accountUserId) }
        if (!shouldContinue()) return
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after remote session invalidation" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        val accountUserId = (state.value as? AuthState.Authenticated)?.userId
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            LocalAccountDataCleaner.wipe(accountUserId)
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun isInvalidRemoteSessionError(error: Throwable): Boolean {
        val restError = error.findCause<RestException>()
        if (restError?.statusCode == 401 || restError?.statusCode == 403) return true

        val message = buildString {
            append(error.message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()

        return (
            "jwt" in message &&
                ("invalid" in message || "expired" in message || "malformed" in message)
            ) || (
            "user" in message &&
                ("does not exist" in message || "not found" in message || "deleted" in message)
            ) || (
            "foreign key" in message &&
                ("auth.users" in message || "user_id" in message)
            )
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
