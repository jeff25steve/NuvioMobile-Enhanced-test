package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.statement.bodyAsText
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface DeviceLinkAuthState {
    data object Idle : DeviceLinkAuthState
    data object Starting : DeviceLinkAuthState
    data class Waiting(
        val code: String,
        val verificationUrl: String,
        val isCompleting: Boolean = false,
    ) : DeviceLinkAuthState
    data class Failed(val reason: DeviceLinkAuthFailure) : DeviceLinkAuthState
}

enum class DeviceLinkAuthFailure {
    Start,
    Expired,
    Complete,
}

object DeviceLinkAuthRepository {
    private const val maxConsecutivePollFailures = 3
    private const val maxPollAttempts = 120
    private const val officialLinkUrl = "https://nuvio.tv/link"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("DeviceLinkAuthRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val lifecycleLock = SynchronizedObject()
    private val flowGate = DeviceLinkFlowGate()
    private val _state = MutableStateFlow<DeviceLinkAuthState>(DeviceLinkAuthState.Idle)
    val state: StateFlow<DeviceLinkAuthState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun start() {
        val job = synchronized(lifecycleLock) {
            if (_state.value is DeviceLinkAuthState.Starting || _state.value is DeviceLinkAuthState.Waiting) {
                null
            } else {
                val attempt = flowGate.beginAttempt()
                if (attempt == null) {
                    null
                } else {
                    _state.value = DeviceLinkAuthState.Starting
                    scope.launch(start = CoroutineStart.LAZY) {
                        runAttempt(attempt)
                    }.also { activeJob = it }
                }
            }
        } ?: return
        job.start()
    }

    fun cancel() {
        val job = synchronized(lifecycleLock) {
            flowGate.cancelAttempt()
            activeJob.also {
                activeJob = null
                _state.value = DeviceLinkAuthState.Idle
            }
        }
        job?.cancel()
    }

    /**
     * Called before any session or server configuration is changed. New device-link attempts
     * remain blocked until the server switch either completes or fails and is rolled back.
     */
    fun beginServerSwitch() {
        val job = synchronized(lifecycleLock) {
            flowGate.beginServerSwitch()
            activeJob.also {
                activeJob = null
                _state.value = DeviceLinkAuthState.Idle
            }
        }
        job?.cancel()
    }

    fun endServerSwitch() {
        flowGate.endServerSwitch()
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun runAttempt(attempt: Long) {
        try {
            val configuration = ServerConfigurationRepository.active.value
            val client = SupabaseProvider.client
            ensureAttemptCurrent(attempt)
            val nonce = Uuid.random().toString()
            val session = startSession(
                client = client,
                configuration = configuration,
                nonce = nonce,
                deviceName = currentDeviceClientMetadata().deviceName,
                attempt = attempt,
            )
            publishState(
                attempt,
                DeviceLinkAuthState.Waiting(
                    code = formatDeviceLinkCode(session.userCode),
                    verificationUrl = session.verificationUriComplete,
                ),
            )
            pollAndComplete(client, session, nonce, attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeviceLinkAuthException) {
            log.w(error) { "Device link sign-in stopped" }
            publishState(attempt, DeviceLinkAuthState.Failed(error.reason))
        } catch (error: Throwable) {
            log.w(error) { "Device link sign-in failed" }
            publishState(attempt, DeviceLinkAuthState.Failed(DeviceLinkAuthFailure.Start))
        } finally {
            flowGate.finishAttempt(attempt)
            val completedJob = currentCoroutineContext()[Job]
            synchronized(lifecycleLock) {
                if (activeJob === completedJob) activeJob = null
            }
        }
    }

    private fun publishState(attempt: Long, next: DeviceLinkAuthState) {
        synchronized(lifecycleLock) {
            if (flowGate.isCurrent(attempt)) _state.value = next
        }
    }

    private suspend fun ensureAttemptCurrent(attempt: Long) {
        currentCoroutineContext().ensureActive()
        if (!flowGate.isCurrent(attempt)) {
            throw CancellationException("Device-link attempt was superseded")
        }
    }

    private suspend fun startSession(
        client: SupabaseClient,
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
        attempt: Long,
    ): DeviceLinkStartResponse {
        ensureAttemptCurrent(attempt)
        val params = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", configuration.deviceLinkUrl())
            put("p_device_name", deviceName)
            put("p_device_type", "mobile")
        }
        val response = client.postgrest
            .rpc("start_device_login_session", params)
            .decodeList<DeviceLinkStartResponse>()
        ensureAttemptCurrent(attempt)
        return response.firstOrNull()
            ?.takeIf {
                it.deviceCode.isNotBlank() &&
                    it.userCode.isNotBlank() &&
                    it.verificationUriComplete.isNotBlank()
            }
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    private suspend fun pollAndComplete(
        client: SupabaseClient,
        session: DeviceLinkStartResponse,
        nonce: String,
        attempt: Long,
    ) {
        var pollAttempts = 0
        var consecutiveFailures = 0
        val intervalMillis = session.pollIntervalSeconds.coerceIn(2, 10) * 1_000L

        while (currentCoroutineContext().isActive && pollAttempts < maxPollAttempts) {
            delay(intervalMillis)
            ensureAttemptCurrent(attempt)
            pollAttempts += 1
            val poll = try {
                val params = buildJsonObject {
                    put("p_code", session.deviceCode)
                    put("p_device_nonce", nonce)
                }
                val response = client.postgrest
                    .rpc("poll_tv_login_session", params)
                    .decodeList<DeviceLinkPollResponse>()
                ensureAttemptCurrent(attempt)
                response.firstOrNull()
                    ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ensureAttemptCurrent(attempt)
                consecutiveFailures += 1
                if (consecutiveFailures >= maxConsecutivePollFailures) throw error
                continue
            }
            consecutiveFailures = 0

            when (poll.status.trim().lowercase()) {
                "pending" -> Unit
                "approved" -> {
                    publishState(
                        attempt,
                        DeviceLinkAuthState.Waiting(
                            code = formatDeviceLinkCode(session.userCode),
                            verificationUrl = session.verificationUriComplete,
                            isCompleting = true,
                        ),
                    )
                    completeSession(client, session.deviceCode, nonce, attempt)
                    return
                }
                else -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
            }
        }

        ensureAttemptCurrent(attempt)
        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    private suspend fun completeSession(
        client: SupabaseClient,
        deviceCode: String,
        nonce: String,
        attempt: Long,
    ) {
        try {
            ensureAttemptCurrent(attempt)
            val payload = buildJsonObject {
                put("code", deviceCode)
                put("device_nonce", nonce)
            }
            val response = client.functions.invoke("tv-logins-exchange", payload)
            ensureAttemptCurrent(attempt)
            val result = json.decodeFromString<DeviceLinkExchangeResponse>(response.bodyAsText())
            val user = result.user ?: client.auth.retrieveUser(result.accessToken).also {
                ensureAttemptCurrent(attempt)
            }
            val expiresIn = requireNotNull(result.expiresIn?.takeIf { it > 0L })
            ensureAttemptCurrent(attempt)
            client.auth.importSession(
                UserSession(
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                    expiresIn = expiresIn,
                    tokenType = result.tokenType?.takeIf { it.isNotBlank() } ?: "bearer",
                    user = user,
                ),
            )
            ensureAttemptCurrent(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ensureAttemptCurrent(attempt)
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete, error)
        }
    }

    private fun ServerConfiguration.deviceLinkUrl(): String =
        if (isCustom) "${backendUrl.trimEnd('/')}/link" else officialLinkUrl
}

internal fun formatDeviceLinkCode(value: String): String {
    val normalized = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
    return if (normalized.length <= 3) normalized else "${normalized.take(3)}-${normalized.drop(3)}"
}

@Serializable
private data class DeviceLinkStartResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class DeviceLinkPollResponse(
    val status: String,
)

@Serializable
private data class DeviceLinkExchangeResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserInfo? = null,
)

private class DeviceLinkAuthException(
    val reason: DeviceLinkAuthFailure,
    cause: Throwable? = null,
) : Exception(reason.name, cause)
