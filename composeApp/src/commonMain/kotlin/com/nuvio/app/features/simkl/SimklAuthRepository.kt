package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.AuthOperationGeneration
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()
    private val authOperationGeneration = AuthOperationGeneration()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.RATINGS,
        ),
    )

    private var hasLoaded = false
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var authenticationMethod = SimklAuthenticationMethod.BROWSER_REDIRECT
    private var pinAuthorizationJob: Job? = null
    private var pinPollingJob: Job? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        authOperationGeneration.advance {
            hasLoaded = false
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            storedState = SimklStoredAuthState()
            accessToken = null
            authenticationMethod = SimklAuthenticationMethod.BROWSER_REDIRECT
            publish()
        }
    }

    fun selectedAuthenticationMethod(): SimklAuthenticationMethod {
        ensureLoaded()
        return authenticationMethod
    }

    fun setAuthenticationMethod(method: SimklAuthenticationMethod) {
        ensureLoaded()
        if (authenticationMethod == method) return
        authOperationGeneration.advance {
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            authenticationMethod = method
            storedState = storedState.copy(authenticationMethod = method.storageValue)
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = null)
        }
    }

    fun pendingDeviceUserCode(): String? {
        ensureLoaded()
        return pendingPinUserCode(storedState.pendingAuthorizationState)
    }

    override fun removeStoredProfile(profileId: Int) {
        if (ProfileRepository.activeProfileId == profileId) {
            authOperationGeneration.advance {
                if (ProfileRepository.activeProfileId == profileId) {
                    pinAuthorizationJob?.cancel()
                    pinAuthorizationJob = null
                    pinPollingJob?.cancel()
                    pinPollingJob = null
                    accessToken = null
                    storedState = SimklStoredAuthState()
                    authenticationMethod = SimklAuthenticationMethod.BROWSER_REDIRECT
                    hasLoaded = false
                    SimklAuthStorage.removeProfile(profileId)
                    publish(isLoading = false, error = null)
                } else {
                    SimklAuthStorage.removeProfile(profileId)
                }
            }
        } else {
            SimklAuthStorage.removeProfile(profileId)
        }
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    fun hasRequiredCredentials(): Boolean = SimklConfig.CLIENT_ID.isNotBlank()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        var deviceCodeFlow = false
        var resultUrl: String? = null
        var generation = 0L
        authOperationGeneration.advance { nextGeneration ->
            generation = nextGeneration
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            clearPendingAuthorization()
            if (authenticationMethod == SimklAuthenticationMethod.DEVICE_CODE) {
                deviceCodeFlow = true
                publish(isLoading = true, error = null)
            } else {
                val material = generateSimklPkceMaterial()
                SimklAuthStorage.saveCodeVerifier(material.verifier)
                storedState = storedState.copy(
                    pendingAuthorizationState = material.state,
                    pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
                )
                persistMetadata()
                publish(error = null)
                resultUrl = authorizationUrl(material)
            }
        }
        if (deviceCodeFlow) {
            pinAuthorizationJob = scope.launch { startPinAuthorization(generation) }
        }
        return resultUrl
    }

    private suspend fun startPinAuthorization(generation: Long) {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/oauth/pin",
                    query = mapOf("client_id" to SimklConfig.CLIENT_ID),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl PIN request failed: ${error.message}" }
            failPinAuthorization(generation)
            return
        }

        val parsed = runCatching { json.decodeFromString<SimklPinResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.result == "OK" && it.userCode.isNotBlank() }
        if (parsed == null) {
            log.w { "Simkl PIN response was not usable" }
            failPinAuthorization(generation)
            return
        }

        val startedAtEpochMs = SimklPlatformClock.nowEpochMs()
        val schedule = buildSimklPinPollingSchedule(
            intervalSeconds = parsed.interval,
            expiresInSeconds = parsed.expiresIn,
            startedAtEpochMs = startedAtEpochMs,
        )
        val committed = authOperationGeneration.commitIfCurrent(generation) {
            storedState = storedState.copy(
                pendingAuthorizationState = buildPendingPinState(parsed.userCode),
                pendingAuthorizationStartedAtEpochMs = startedAtEpochMs,
                pendingPinPollIntervalSeconds = schedule.intervalSeconds,
                pendingPinExpiresAtEpochMs = schedule.expiresAtEpochMs,
            )
            persistMetadata()
            publish(isLoading = false, error = null)
        }
        if (!committed) return
        startPinPolling(
            userCode = parsed.userCode,
            schedule = schedule,
            generation = generation,
        )
    }

    private fun failPinAuthorization(generation: Long) {
        authOperationGeneration.commitIfCurrent(generation) {
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
        }
    }

    private fun startPinPolling(
        userCode: String,
        schedule: SimklPinPollingSchedule,
        generation: Long,
    ) {
        pinPollingJob?.cancel()
        pinPollingJob = scope.launch {
            while (
                authOperationGeneration.isCurrent(generation) &&
                pendingPinUserCode(storedState.pendingAuthorizationState) == userCode &&
                SimklPlatformClock.nowEpochMs() < schedule.expiresAtEpochMs
            ) {
                delay(schedule.intervalSeconds * 1_000L)
                if (!authOperationGeneration.isCurrent(generation)) return@launch
                if (SimklPlatformClock.nowEpochMs() >= schedule.expiresAtEpochMs) break
                if (pollPin(userCode, generation)) return@launch
            }

            authOperationGeneration.commitIfCurrent(generation) {
                if (pendingPinUserCode(storedState.pendingAuthorizationState) == userCode) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                }
            }
        }
    }

    private suspend fun pollPin(userCode: String, generation: Long): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = buildSimklPinPollPath(userCode),
                    query = mapOf("client_id" to SimklConfig.CLIENT_ID),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.d { "Simkl PIN poll failed: ${error.message}" }
            return false
        }

        val parsed = runCatching { json.decodeFromString<SimklPinPollResponse>(response.body) }
            .getOrNull() ?: return false
        return when (simklPinPollOutcome(parsed)) {
            SimklPinPollOutcome.AUTHORIZED -> {
                val token = parsed.accessToken ?: return false
                applyAccessToken(token, expiresInSeconds = null, generation = generation)
                authOperationGeneration.isCurrent(generation)
            }
            SimklPinPollOutcome.CODE_EXPIRED -> {
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                }
                true
            }
            SimklPinPollOutcome.PENDING,
            SimklPinPollOutcome.UNEXPECTED -> false
        }
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        if (pendingPinUserCode(state) != null) return SIMKL_PIN_VERIFICATION_URL
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        authOperationGeneration.advance {
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = null)
        }
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        if (pendingPinUserCode(storedState.pendingAuthorizationState) != null) return false
        val generation = authOperationGeneration.current()
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(error = SimklAuthError.INVALID_CALLBACK)
                }
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback, generation) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        authOperationGeneration.advance {
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            accessToken = null
            SimklAuthStorage.saveAccessToken(null)
            clearPendingAuthorization()
            storedState = SimklStoredAuthState()
            persistMetadata()
            publish(error = null)
        }
        SimklSyncRepository.clearLocalState()
    }

    internal fun authorizedAccessToken(): String? = authorizedAccessTokenSnapshot()?.value

    internal fun authorizedAccessTokenSnapshot(): SimklApiAccessToken? {
        ensureLoaded()
        val snapshot = authOperationGeneration.read { generation ->
            val token = accessToken?.takeIf(String::isNotBlank) ?: return@read null
            val expiresAt = storedState.tokenExpiresAtEpochMs
            if (expiresAt != null && SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS) {
                ExpiredSimklAccessToken(generation)
            } else {
                SimklApiAccessToken(token, generation)
            }
        }
        return when (snapshot) {
            is ExpiredSimklAccessToken -> {
                invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED, snapshot.generation)
                null
            }
            is SimklApiAccessToken -> snapshot
            else -> null
        }
    }

    internal fun onUnauthorizedResponse(generation: Long) {
        invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED, generation)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        val generation = authOperationGeneration.current()
        return if (fetchAndStoreUserSettings(generation = generation)) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        val generation = authOperationGeneration.current()
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                authOperationGeneration.commitIfCurrent(generation) {
                    storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                    persistMetadata()
                }
            }
            SimklSettingsRefreshAction.FETCH -> fetchAndStoreUserSettings(activityWatermark, generation)
        }
    }

    private suspend fun completeAuthorization(
        callback: SimklAuthCallback.AuthorizationCode,
        generation: Long,
    ) = authorizationMutex.withLock {
            if (!authOperationGeneration.isCurrent(generation)) return@withLock
            authOperationGeneration.commitIfCurrent(generation) { publish(isLoading = true, error = null) }
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                }
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                }
                return@withLock
            }

            val request = SimklTokenRequest(
                grantType = SIMKL_GRANT_AUTHORIZATION_CODE,
                code = callback.code,
                clientId = SimklConfig.CLIENT_ID,
                codeVerifier = verifier,
                redirectUri = SimklConfig.REDIRECT_URI,
            )
            val response = try {
                SimklApi.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = "/oauth/token",
                        body = json.encodeToString(request),
                        requiresAuthentication = false,
                        retryPolicy = SimklRetryPolicy.NEVER,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl token exchange failed: ${error.message}" }
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                }
                return@withLock
            }
            val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
                .getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (token == null) {
                authOperationGeneration.commitIfCurrent(generation) {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
                }
                return@withLock
            }

            applyAccessToken(token.accessToken, token.expiresIn, generation)
        }

    private suspend fun applyAccessToken(
        token: String,
        expiresInSeconds: Long?,
        generation: Long,
    ) {
        val committed = authOperationGeneration.commitIfCurrent(generation) {
            accessToken = token
            SimklAuthStorage.saveAccessToken(token)
            clearPendingAuthorization()
            storedState = storedState.copy(
                tokenExpiresAtEpochMs = expiresInSeconds
                    ?.takeIf { seconds -> seconds > 0L }
                    ?.let { seconds -> SimklPlatformClock.nowEpochMs() + seconds * 1_000L },
            )
            persistMetadata()
            publish(isLoading = false, error = null)
        }
        if (!committed) return
        fetchAndStoreUserSettings(generation = generation)
        if (authOperationGeneration.isCurrent(generation)) {
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }
    }

    private suspend fun fetchAndStoreUserSettings(
        activityWatermark: String? = null,
        generation: Long = authOperationGeneration.current(),
    ): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        return authOperationGeneration.commitIfCurrent(generation) {
            storedState = storedState.copy(
                username = settings.user?.name,
                accountId = settings.account?.id,
                hasFetchedUserSettings = true,
                settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
            )
            persistMetadata()
            publish(error = null)
        }
    }

    private fun loadFromDisk() {
        var restoredPin: Pair<String, SimklPinPollingSchedule>? = null
        val generation = authOperationGeneration.advance { nextGeneration ->
            hasLoaded = true
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            storedState = SimklAuthStorage.loadMetadataPayload()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { payload ->
                    runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                        .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                        .getOrNull()
                }
                ?: SimklStoredAuthState()
            authenticationMethod = SimklAuthenticationMethod.fromStorageValue(storedState.authenticationMethod)
            accessToken = SimklAuthStorage.loadAccessToken()?.takeIf(String::isNotBlank)
            val now = SimklPlatformClock.nowEpochMs()
            if (accessToken != null && storedState.tokenExpiresAtEpochMs?.let { expiresAt ->
                    now >= expiresAt - TOKEN_EXPIRY_SKEW_MS
                } == true
            ) {
                accessToken = null
                SimklAuthStorage.saveAccessToken(null)
                storedState = SimklStoredAuthState()
                persistMetadata()
            }
            if (shouldClearOrphanedSimklCodeVerifier(storedState)) {
                SimklAuthStorage.saveCodeVerifier(null)
            }

            val pinCode = pendingPinUserCode(storedState.pendingAuthorizationState)
            if (pinCode != null) {
                val startedAt = storedState.pendingAuthorizationStartedAtEpochMs ?: now
                val migratedSchedule = buildSimklPinPollingSchedule(
                    intervalSeconds = storedState.pendingPinPollIntervalSeconds,
                    expiresInSeconds = null,
                    startedAtEpochMs = startedAt,
                )
                val expiresAt = storedState.pendingPinExpiresAtEpochMs ?: migratedSchedule.expiresAtEpochMs
                val interval = (storedState.pendingPinPollIntervalSeconds
                    ?: SIMKL_DEFAULT_PIN_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(1)
                storedState = storedState.copy(
                    pendingAuthorizationStartedAtEpochMs = startedAt,
                    pendingPinPollIntervalSeconds = interval,
                    pendingPinExpiresAtEpochMs = expiresAt,
                )
                if (isSimklPinAuthorizationExpired(expiresAt, now)) {
                    clearPendingAuthorization()
                    persistMetadata()
                } else {
                    persistMetadata()
                    restoredPin = pinCode to SimklPinPollingSchedule(interval, expiresAt)
                }
            } else if (storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                    startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                    nowEpochMs = now,
                )
            ) {
                clearPendingAuthorization()
                persistMetadata()
            }
            val restoredUiState = simklAuthStateAfterProfileReload(
                storedState = storedState,
                accessToken = accessToken,
                credentialsConfigured = hasRequiredCredentials(),
            )
            _isAuthenticated.value = restoredUiState.mode == SimklConnectionMode.CONNECTED
            _uiState.value = restoredUiState
        }
        restoredPin?.let { (userCode, schedule) ->
            startPinPolling(userCode, schedule, generation)
        }
    }

    private fun invalidateCredentials(error: SimklAuthError, generation: Long) {
        val invalidated = authOperationGeneration.invalidateIfCurrent(generation) {
            pinAuthorizationJob?.cancel()
            pinAuthorizationJob = null
            pinPollingJob?.cancel()
            pinPollingJob = null
            accessToken = null
            SimklAuthStorage.saveAccessToken(null)
            clearPendingAuthorization()
            storedState = SimklStoredAuthState()
            persistMetadata()
            publish(isLoading = false, error = error)
        }
        if (invalidated) SimklSyncRepository.clearLocalState()
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
            pendingPinPollIntervalSeconds = null,
            pendingPinExpiresAtEpochMs = null,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
}

private data class ExpiredSimklAccessToken(val generation: Long)

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
