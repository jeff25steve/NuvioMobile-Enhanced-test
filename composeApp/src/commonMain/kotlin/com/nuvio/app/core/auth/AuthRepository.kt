package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.concurrency.cancelAndJoinNonCancellable
import com.nuvio.app.core.concurrency.runCatchingUnlessCancelled
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private fun cleanupFailure(results: List<Result<Unit>>): Throwable? {
    val failures = results.mapNotNull { it.exceptionOrNull() }
    val primary = failures.firstOrNull { it is CancellationException }
        ?: failures.firstOrNull()
        ?: return null
    failures.filter { it !== primary }.forEach { failure ->
        primary.addSuppressed(failure)
    }
    return primary
}

internal suspend fun ensureCleanupCallerActive(results: List<Result<Unit>>) {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancellation: CancellationException) {
        results.mapNotNull { it.exceptionOrNull() }
            .filter { it !== cancellation }
            .forEach(cancellation::addSuppressed)
        throw cancellation
    }
}

internal suspend fun runInvalidSessionCleanup(
    clearAnonymousUserId: suspend () -> Unit,
    clearRemoteSession: suspend () -> Unit,
    clearLocalAccountData: suspend () -> Unit,
    shouldContinue: () -> Boolean = { true },
): Result<Unit> {
    val results = withContext(NonCancellable) {
        val anonymous = if (shouldContinue()) {
            runCatching { clearAnonymousUserId() }
        } else {
            Result.success(Unit)
        }
        val remote = if (shouldContinue()) {
            runCatching { clearRemoteSession() }
        } else {
            Result.success(Unit)
        }
        val local = if (shouldContinue()) {
            runCatching { clearLocalAccountData() }
        } else {
            Result.success(Unit)
        }
        listOf(anonymous, remote, local)
    }

    val failure = cleanupFailure(results)
    // Caller cancellation takes precedence without discarding cleanup diagnostics.
    ensureCleanupCallerActive(results)

    if (failure is CancellationException) throw failure
    return if (failure == null) Result.success(Unit) else Result.failure(failure)
}

internal suspend fun runServerSwitchCleanup(
    clearAnonymousUserId: suspend () -> Unit,
    clearRemoteSession: suspend () -> Unit,
    clearLocalAccountData: suspend () -> Unit,
): Result<Unit> {
    // Both credential stores must be attempted even if cancellation arrives while the
    // first suspending clear is in flight. Returning from this block still propagates
    // caller cancellation, so local account data is never wiped by a cancelled switch.
    val credentialResults = withContext(NonCancellable) {
        listOf(
            runCatching { clearAnonymousUserId() },
            runCatching { clearRemoteSession() },
        )
    }
    // NonCancellable may return inline without a dispatcher handoff.
    ensureCleanupCallerActive(credentialResults)

    val credentialFailure = cleanupFailure(credentialResults)
    if (credentialFailure is CancellationException) throw credentialFailure
    if (credentialFailure != null) return Result.failure(credentialFailure)

    return try {
        clearLocalAccountData()
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")
    private val sessionGeneration = AuthSessionGeneration()
    private val sessionOperationGate = AuthSessionOperationGate()

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private var trustNextSessionWithoutValidation = false

    /** Captures account/backend identity without copying any credential material. */
    internal fun captureSessionIdentity(): AuthSessionIdentity? {
        val authenticated = state.value as? AuthState.Authenticated ?: return null
        val userId = authenticated.userId.takeIf { it.isNotBlank() } ?: return null
        val identity = AuthSessionIdentity(
            sessionGeneration = sessionGeneration.current(),
            userId = userId,
            isAnonymous = authenticated.isAnonymous,
            serverConfiguration = ServerConfigurationRepository.active.value,
        )
        // Do not start account-scoped work while the SDK already holds a different session
        // but Nuvio's sessionStatus collector has not published that transition yet.
        val supabaseUserId = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id
        val latestAuthState = state.value as? AuthState.Authenticated ?: return null
        return identity.takeIf {
            isAuthSessionIdentityCurrent(
                expected = identity,
                currentSessionGeneration = sessionGeneration.current(),
                currentUserId = latestAuthState.userId,
                currentIsAnonymous = latestAuthState.isAnonymous,
                currentServerConfiguration = ServerConfigurationRepository.active.value,
                currentSupabaseUserId = supabaseUserId,
            )
        }
    }

    private fun isCurrentSessionIdentity(expected: AuthSessionIdentity): Boolean {
        if (!sessionGeneration.isCurrent(expected.sessionGeneration)) return false
        val configuration = ServerConfigurationRepository.active.value
        if (configuration != expected.serverConfiguration) return false
        val authenticated = state.value as? AuthState.Authenticated ?: return false
        if (authenticated.userId != expected.userId || authenticated.isAnonymous != expected.isAnonymous) {
            return false
        }
        val currentSupabaseUserId = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id
        return isAuthSessionIdentityCurrent(
            expected = expected,
            currentSessionGeneration = sessionGeneration.current(),
            currentUserId = authenticated.userId,
            currentIsAnonymous = authenticated.isAnonymous,
            currentServerConfiguration = configuration,
            currentSupabaseUserId = currentSupabaseUserId,
        )
    }

    /**
     * Commits a synchronous local update only while the originating Supabase identity is current.
     * The session-generation lock serializes this check and update with auth-state publication
     * and reset, so a replacement session cannot slip between the check and the commit.
     */
    internal fun runIfSessionIdentityCurrent(
        expected: AuthSessionIdentity,
        action: () -> Unit,
    ): Boolean {
        var committed = false
        sessionGeneration.runIfCurrent(expected.sessionGeneration) {
            val authenticated = state.value as? AuthState.Authenticated
            val configuration = ServerConfigurationRepository.active.value
            val supabaseUserId = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id
            if (
                isAuthSessionIdentityCurrent(
                    expected = expected,
                    currentSessionGeneration = expected.sessionGeneration,
                    currentUserId = authenticated?.userId,
                    currentIsAnonymous = authenticated?.isAnonymous,
                    currentServerConfiguration = configuration,
                    currentSupabaseUserId = supabaseUserId,
                )
            ) {
                action()
                committed = true
            }
        }
        return committed
    }

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
                if (error.isInvalidSessionError()) {
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
        val userId = Uuid.random().toString()
        sessionOperationGate.runIfOpen {
            _error.value = null
            AuthStorage.saveAnonymousUserId(userId)
            _state.value = AuthState.Authenticated(
                userId = userId,
                email = null,
                isAnonymous = true,
            )
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = withSessionOperation {
        val client = SupabaseProvider.client
        val generation = sessionGeneration.current()
        runCatchingUnlessCancelled {
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

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = withSessionOperation {
        val client = SupabaseProvider.client
        val generation = sessionGeneration.current()
        runCatchingUnlessCancelled {
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

    internal fun beginSessionImport(job: Job): Long? =
        sessionOperationGate.beginOperation(job)

    internal fun finishSessionImport(operation: Long) {
        sessionOperationGate.finishOperation(operation)
    }

    private suspend fun <T> withSessionOperation(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
            ?: error("Authentication session operation requires a coroutine Job")
        val operation = beginSessionImport(job)
            ?: throw CancellationException("Authentication is paused during a session reset")
        return try {
            block()
        } finally {
            finishSessionImport(operation)
        }
    }

    /**
     * Blocks new credential operations, cancels existing operations and waits for their
     * Supabase session imports to finish unwinding before the old client's session is cleared.
     */
    private suspend fun beginSessionReset(expectedIdentity: AuthSessionIdentity? = null): Boolean {
        val operations = sessionOperationGate.beginReset()
        try {
            // Close the gate and let any existing auth import settle before checking identity.
            cancelAndJoinNonCancellable(operations)
            if (expectedIdentity != null && !isCurrentSessionIdentity(expectedIdentity)) {
                sessionOperationGate.endReset()
                return false
            }
            sessionGeneration.invalidate()
            trustNextSessionWithoutValidation = false
            return true
        } catch (error: Throwable) {
            // The lease has been acquired. Restore a live session-status collector and
            // release reset ownership even when this caller is cancelled while joining.
            withContext(NonCancellable) {
                try {
                    reinitialize(publishLoadingState = false)
                } finally {
                    sessionOperationGate.endReset()
                }
            }
            throw error
        }
    }

    suspend fun beginServerSwitch() {
        beginSessionReset()
    }

    private fun endSessionReset() {
        sessionOperationGate.endReset()
    }

    fun endServerSwitch() = endSessionReset()

    suspend fun signOut(): Result<Unit> {
        beginSessionReset()
        try {
            _error.value = null
            val client = SupabaseProvider.client
            val accountUserId = (state.value as? AuthState.Authenticated)?.userId
            val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
            val wasAnonymous = anonymousRead.getOrNull() != null
            val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
            validatedRemoteUserId = null
            val remoteSignOut = if (wasAnonymous) {
                Result.success(Unit)
            } else {
                runCatching { client.auth.signOut() }
            }

            val (fallbackSessionClear, localCleanup) = withContext(NonCancellable) {
                _state.value = AuthState.Unauthenticated
                val fallback = if (remoteSignOut.isFailure) {
                    runCatching { client.auth.clearSession() }
                        .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
                } else {
                    Result.success(Unit)
                }
                val cleanup = runCatching { LocalAccountDataCleaner.wipe(accountUserId) }
                fallback to cleanup
            }
            ensureCleanupCallerActive(
                listOf(anonymousRead, anonymousClear, remoteSignOut, fallbackSessionClear, localCleanup),
            )

            val cleanupResults = listOf(
                anonymousRead,
                anonymousClear,
                remoteSignOut,
                fallbackSessionClear,
                localCleanup,
            )
            val failure = cleanupFailure(cleanupResults)
            if (failure is CancellationException) throw failure
            return if (failure == null) {
                Result.success(Unit)
            } else {
                log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
                _error.value = failure.message ?: runCatching {
                    getString(Res.string.auth_sign_out_failed)
                }.getOrDefault("Sign out failed")
                Result.failure(failure)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    // Keep the existing logged-out state while restarting the generation-scoped collector.
                    reinitialize(publishLoadingState = false)
                } finally {
                    endSessionReset()
                }
            }
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        _error.value = null
        sessionGeneration.invalidate()
        trustNextSessionWithoutValidation = false
        val accountUserId =
            SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id?.takeIf { it.isNotBlank() }
                ?: (state.value as? AuthState.Authenticated)?.userId?.takeIf { it.isNotBlank() }
                ?: validatedRemoteUserId?.takeIf { it.isNotBlank() }
                ?: AuthStorage.loadAnonymousUserId()?.takeIf { it.isNotBlank() }
        validatedRemoteUserId = null

        val cleanup = runServerSwitchCleanup(
            clearAnonymousUserId = { AuthStorage.clearAnonymousUserId() },
            clearRemoteSession = { SupabaseProvider.client.auth.clearSession() },
            clearLocalAccountData = { LocalAccountDataCleaner.wipe(accountUserId) },
        )
        _state.value = AuthState.Unauthenticated
        return cleanup
    }

    fun reinitialize(publishLoadingState: Boolean = true) {
        sessionGeneration.invalidate()
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        if (publishLoadingState) _state.value = AuthState.Loading
        initialize()
    }

    internal suspend fun signOutIfSessionInvalid(
        error: Throwable,
        source: String,
        expectedIdentity: AuthSessionIdentity,
    ): Boolean {
        if (!isInvalidRemoteSessionError(error)) return false

        // A delayed error from an old request must never clear a replacement account/backend.
        if (!beginSessionReset(expectedIdentity)) return false
        try {
            log.w(error) { "$source failed because the originating Supabase session is invalid; clearing that account's local auth" }
            val client = SupabaseProvider.client
            clearLocalSessionAfterRemoteInvalidation(
                accountUserId = expectedIdentity.userId,
                clearSession = { client.auth.clearSession() },
            )
            return true
        } finally {
            withContext(NonCancellable) {
                try {
                    reinitialize(publishLoadingState = false)
                } finally {
                    endSessionReset()
                }
            }
        }
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation(
        accountUserId: String?,
        clearSession: suspend () -> Unit,
        shouldContinue: () -> Boolean = { true },
    ) {
        if (!shouldContinue()) {
            currentCoroutineContext().ensureActive()
            return
        }
        _error.value = null
        validatedRemoteUserId = null
        val cleanup = runInvalidSessionCleanup(
            clearAnonymousUserId = { AuthStorage.clearAnonymousUserId() },
            clearRemoteSession = clearSession,
            clearLocalAccountData = {
                _state.value = AuthState.Unauthenticated
                LocalAccountDataCleaner.wipe(accountUserId)
            },
            shouldContinue = shouldContinue,
        )
        cleanup.onFailure { error ->
            log.w(error) { "Invalid-session cleanup completed with one or more failures" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> {
        beginSessionReset()
        try {
            _error.value = null
            val accountUserId = (state.value as? AuthState.Authenticated)?.userId
            val client = SupabaseProvider.client

            try {
                client.functions.invoke("delete-account")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "Account deletion request failed" }
                _error.value = error.message ?: getString(Res.string.auth_account_deletion_failed)
                return Result.failure(error)
            }

            // The delete endpoint has confirmed the irreversible remote action. The logout
            // request remains cancellable; local session removal and device cleanup must still
            // finish even if the request or the screen's coroutine is cancelled.
            val remoteSignOut = runCatching { client.auth.signOut() }
            remoteSignOut.onFailure { error ->
                log.w(error) { "Remote sign-out after account deletion failed; clearing the local session directly" }
            }

            val (localSessionClear, localCleanup) = withContext(NonCancellable) {
                _state.value = AuthState.Unauthenticated
                val clear = runCatching { client.auth.clearSession() }
                val cleanup = runCatching { LocalAccountDataCleaner.wipe(accountUserId) }
                clear to cleanup
            }
            ensureCleanupCallerActive(listOf(remoteSignOut, localSessionClear, localCleanup))

            val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
                ?: localSessionClear.exceptionOrNull() as? CancellationException
                ?: localCleanup.exceptionOrNull() as? CancellationException
            if (cancellation != null) throw cancellation

            val failure = cleanupFailure(listOf(localSessionClear, localCleanup))
            return if (failure == null) {
                Result.success(Unit)
            } else {
                log.e(failure) { "Account was deleted remotely but local account cleanup was incomplete" }
                _error.value = failure.message ?: getString(Res.string.auth_account_deletion_failed)
                Result.failure(failure)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    reinitialize(publishLoadingState = false)
                } finally {
                    endSessionReset()
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
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
