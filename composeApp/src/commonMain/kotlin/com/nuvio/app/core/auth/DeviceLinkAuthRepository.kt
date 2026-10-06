package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    private val _state = MutableStateFlow<DeviceLinkAuthState>(DeviceLinkAuthState.Idle)
    val state: StateFlow<DeviceLinkAuthState> = _state.asStateFlow()
    private var activeJob: Job? = null

    @OptIn(ExperimentalUuidApi::class)
    fun start() {
        if (_state.value is DeviceLinkAuthState.Starting || _state.value is DeviceLinkAuthState.Waiting) return

        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = DeviceLinkAuthState.Starting
            val configuration = ServerConfigurationRepository.active.value
            val nonce = Uuid.random().toString()
            try {
                val anonymousAccessToken = createAnonymousAccessToken(configuration)
                val session = startSession(
                    configuration = configuration,
                    nonce = nonce,
                    deviceName = currentDeviceClientMetadata().deviceName,
                    accessToken = anonymousAccessToken,
                )
                _state.value = DeviceLinkAuthState.Waiting(
                    code = formatDeviceLinkCode(session.userCode),
                    verificationUrl = session.verificationUriComplete,
                )
                pollAndComplete(
                    configuration = configuration,
                    session = session,
                    nonce = nonce,
                    accessToken = anonymousAccessToken,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceLinkAuthException) {
                log.w(error) { "Device link sign-in stopped" }
                _state.value = DeviceLinkAuthState.Failed(error.reason)
            } catch (error: Throwable) {
                log.w(error) { "Device link sign-in failed" }
                _state.value = DeviceLinkAuthState.Failed(DeviceLinkAuthFailure.Start)
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = DeviceLinkAuthState.Idle
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun createAnonymousAccessToken(
        configuration: ServerConfiguration,
    ): String {
        val response = SupabaseProvider.client.httpClient.request(
            "${configuration.backendUrl.trimEnd('/')}/auth/v1/signup",
        ) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("apikey", configuration.publishableKey)
            header(HttpHeaders.Authorization, "Bearer " + configuration.publishableKey)
            setBody(
                buildJsonObject {
                    put("data", buildJsonObject {
                        put("tv_client", "mobile")
                    })
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }
        return json.decodeFromString<AnonymousAuthResponse>(response.bodyAsText())
            .accessToken
            .takeIf(String::isNotBlank)
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun startSession(
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
        accessToken: String,
    ): DeviceLinkStartResponse {
        val params = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", configuration.deviceLinkUrl())
            put("p_device_name", deviceName)
            put("p_device_type", "mobile")
        }
        val response = SupabaseProvider.client.httpClient.request(
            "${configuration.backendUrl.trimEnd('/')}/rest/v1/rpc/start_device_login_session",
        ) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("apikey", configuration.publishableKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            setBody(params.toString())
        }
        if (!response.status.isSuccess()) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }
        return json.decodeFromString<List<DeviceLinkStartResponse>>(response.bodyAsText())
            .firstOrNull()
            ?.takeIf {
                it.deviceCode.isNotBlank() &&
                    it.userCode.isNotBlank() &&
                    it.verificationUriComplete.isNotBlank()
            }
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun pollAndComplete(
        configuration: ServerConfiguration,
        session: DeviceLinkStartResponse,
        nonce: String,
        accessToken: String,
    ) {
        var pollAttempts = 0
        var consecutiveFailures = 0
        val intervalMillis = session.pollIntervalSeconds.coerceIn(2, 10) * 1_000L

        while (currentCoroutineContext().isActive && pollAttempts < maxPollAttempts) {
            delay(intervalMillis)
            pollAttempts += 1
            val poll = try {
                val params = buildJsonObject {
                    put("p_code", session.deviceCode)
                    put("p_device_nonce", nonce)
                }
                val response = SupabaseProvider.client.httpClient.request(
                    "${configuration.backendUrl.trimEnd('/')}/rest/v1/rpc/poll_tv_login_session",
                ) {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    header("apikey", configuration.publishableKey)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    setBody(params.toString())
                }
                if (!response.status.isSuccess()) {
                    throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
                }
                json.decodeFromString<List<DeviceLinkPollResponse>>(response.bodyAsText())
                    .firstOrNull()
                    ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                consecutiveFailures += 1
                if (consecutiveFailures >= maxConsecutivePollFailures) throw error
                continue
            }
            consecutiveFailures = 0

            when (poll.status.trim().lowercase()) {
                "pending" -> Unit
                "approved" -> {
                    _state.value = DeviceLinkAuthState.Waiting(
                        code = formatDeviceLinkCode(session.userCode),
                        verificationUrl = session.verificationUriComplete,
                        isCompleting = true,
                    )
                    completeSession(
                        configuration = configuration,
                        deviceCode = session.deviceCode,
                        nonce = nonce,
                        accessToken = accessToken,
                    )
                    return
                }
                else -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
            }
        }

        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun completeSession(
        configuration: ServerConfiguration,
        deviceCode: String,
        nonce: String,
        accessToken: String,
    ) {
        try {
            val payload = buildJsonObject {
                put("code", deviceCode)
                put("device_nonce", nonce)
            }
            val response = SupabaseProvider.client.httpClient.request(
                "${configuration.backendUrl.trimEnd('/')}/functions/v1/tv-logins-exchange",
            ) {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                header("apikey", configuration.publishableKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(payload.toString())
            }
            if (!response.status.isSuccess()) {
                throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            }
            val result = json.decodeFromString<DeviceLinkExchangeResponse>(response.bodyAsText())
            val user = result.user ?: SupabaseProvider.client.auth.retrieveUser(result.accessToken)
            val expiresIn = requireNotNull(result.expiresIn?.takeIf { it > 0L })
            SupabaseProvider.client.auth.importSession(
                UserSession(
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                    expiresIn = expiresIn,
                    tokenType = result.tokenType?.takeIf { it.isNotBlank() } ?: "bearer",
                    user = user,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
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
private data class AnonymousAuthResponse(
    @SerialName("access_token") val accessToken: String,
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
