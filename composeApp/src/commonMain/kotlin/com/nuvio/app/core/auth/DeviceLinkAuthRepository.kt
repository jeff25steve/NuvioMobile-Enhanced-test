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
                val started = startSession(
                    configuration = configuration,
                    nonce = nonce,
                    deviceName = currentDeviceClientMetadata().deviceName,
                    accessToken = anonymousAccessToken,
                )
                _state.value = DeviceLinkAuthState.Waiting(
                    code = formatDeviceLinkCode(started.session.userCode),
                    verificationUrl = started.session.verificationUriComplete,
                )
                pollAndComplete(
                    configuration = configuration,
                    session = started.session,
                    nonce = nonce,
                    accessToken = started.accessToken,
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
        val signupResponse = SupabaseProvider.client.httpClient.request(
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
        val signupBody = signupResponse.bodyAsText()
        if (signupResponse.status.isSuccess()) {
            parseAccessToken(signupBody)?.let { return it }
        }

        val anonymousTokenResponse = SupabaseProvider.client.httpClient.request(
            "${configuration.backendUrl.trimEnd('/')}/auth/v1/token?grant_type=anonymous",
        ) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("apikey", configuration.publishableKey)
            header(HttpHeaders.Authorization, "Bearer " + configuration.publishableKey)
            setBody("{}")
        }
        if (!anonymousTokenResponse.status.isSuccess()) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }
        return parseAccessToken(anonymousTokenResponse.bodyAsText())
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun startSession(
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
        accessToken: String,
    ): StartSessionResult {
        var callerToken = accessToken
        var response = requestStartDeviceLogin(
            configuration = configuration,
            nonce = nonce,
            deviceName = deviceName,
            accessToken = callerToken,
        )
        var body = response.bodyAsText()

        if (!response.status.isSuccess() && response.status.value == 401 && isCallerSessionRejected(body)) {
            callerToken = createAnonymousAccessToken(configuration)
            response = requestStartDeviceLogin(
                configuration = configuration,
                nonce = nonce,
                deviceName = deviceName,
                accessToken = callerToken,
            )
            body = response.bodyAsText()
        }

        if (response.status.isSuccess()) {
            val session = json.decodeFromString<List<DeviceLinkStartResponse>>(body)
                .firstOrNull()
                ?.takeIf {
                    it.deviceCode.isNotBlank() &&
                        it.userCode.isNotBlank() &&
                        it.verificationUriComplete.isNotBlank()
                }
                ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
            return StartSessionResult(session, callerToken)
        }

        if (!isMissingDeviceLoginFunction(body)) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }

        val legacyResponse = requestLegacyDeviceLogin(
            configuration = configuration,
            nonce = nonce,
            deviceName = deviceName,
            accessToken = callerToken,
        )
        val legacyBody = legacyResponse.bodyAsText()
        if (!legacyResponse.status.isSuccess()) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }

        val legacy = json.decodeFromString<List<LegacyDeviceLinkStartResponse>>(legacyBody)
            .firstOrNull()
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)

        val code = legacy.code.trim()
        val webUrl = legacy.webUrl.trim()
        if (code.isBlank() || webUrl.isBlank()) {
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
        }

        return StartSessionResult(
            session = DeviceLinkStartResponse(
                deviceCode = code,
                userCode = code,
                verificationUri = webUrl,
                verificationUriComplete = webUrl,
                pollIntervalSeconds = legacy.pollIntervalSeconds.coerceAtLeast(1),
            ),
            accessToken = callerToken,
        )
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun requestStartDeviceLogin(
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
        accessToken: String,
    ) = SupabaseProvider.client.httpClient.request(
        "${configuration.backendUrl.trimEnd('/')}/rest/v1/rpc/start_device_login_session",
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header("apikey", configuration.publishableKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        setBody(
            buildJsonObject {
                put("p_device_nonce", nonce)
                put("p_redirect_base_url", configuration.deviceLinkUrl())
                put("p_device_name", deviceName)
                put("p_device_type", "mobile")
            }.toString(),
        )
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun requestLegacyDeviceLogin(
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
        accessToken: String,
    ) = SupabaseProvider.client.httpClient.request(
        "${configuration.backendUrl.trimEnd('/')}/rest/v1/rpc/start_tv_login_session",
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header("apikey", configuration.publishableKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        setBody(
            buildJsonObject {
                put("p_device_nonce", nonce)
                put("p_redirect_base_url", configuration.legacyDeviceLinkUrl())
                put("p_device_name", deviceName)
            }.toString(),
        )
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun pollAndComplete(
        configuration: ServerConfiguration,
        session: DeviceLinkStartResponse,
        nonce: String,
        accessToken: String,
    ) {
        var callerToken = accessToken
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
                var response = requestPoll(
                    configuration = configuration,
                    params = params.toString(),
                    accessToken = callerToken,
                )
                var body = response.bodyAsText()

                if (!response.status.isSuccess() && response.status.value == 401 && isCallerSessionRejected(body)) {
                    callerToken = createAnonymousAccessToken(configuration)
                    response = requestPoll(
                        configuration = configuration,
                        params = params.toString(),
                        accessToken = callerToken,
                    )
                    body = response.bodyAsText()
                }

                if (!response.status.isSuccess()) {
                    throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
                }

                json.decodeFromString<List<DeviceLinkPollResponse>>(body)
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
                        accessToken = callerToken,
                    )
                    return
                }
                else -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
            }
        }

        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun requestPoll(
        configuration: ServerConfiguration,
        params: String,
        accessToken: String,
    ) = SupabaseProvider.client.httpClient.request(
        "${configuration.backendUrl.trimEnd('/')}/rest/v1/rpc/poll_tv_login_session",
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header("apikey", configuration.publishableKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        setBody(params)
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun completeSession(
        configuration: ServerConfiguration,
        deviceCode: String,
        nonce: String,
        accessToken: String,
    ) {
        try {
            var callerToken = accessToken
            val payload = buildJsonObject {
                put("code", deviceCode)
                put("device_nonce", nonce)
            }
            var response = requestExchange(
                configuration = configuration,
                payload = payload.toString(),
                accessToken = callerToken,
            )
            var body = response.bodyAsText()

            if (!response.status.isSuccess() && response.status.value == 401 && isCallerSessionRejected(body)) {
                callerToken = createAnonymousAccessToken(configuration)
                response = requestExchange(
                    configuration = configuration,
                    payload = payload.toString(),
                    accessToken = callerToken,
                )
                body = response.bodyAsText()
            }

            if (!response.status.isSuccess()) {
                throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            }

            val result = json.decodeFromString<DeviceLinkExchangeResponse>(body)
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

    @OptIn(SupabaseInternal::class)
    private suspend fun requestExchange(
        configuration: ServerConfiguration,
        payload: String,
        accessToken: String,
    ) = SupabaseProvider.client.httpClient.request(
        "${configuration.backendUrl.trimEnd('/')}/functions/v1/tv-logins-exchange",
    ) {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header("apikey", configuration.publishableKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        setBody(payload)
    }

    private fun parseAccessToken(body: String): String? =
        runCatching {
            json.decodeFromString<AnonymousAuthResponse>(body).accessToken
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    private fun isCallerSessionRejected(body: String): Boolean {
        val message = body.lowercase()
        return "invalid caller session" in message ||
            "jwt expired" in message ||
            "invalid jwt" in message ||
            "unauthorized" in message
    }

    private fun isMissingDeviceLoginFunction(body: String): Boolean {
        val message = body.lowercase()
        return "could not find the function" in message &&
            "start_device_login_session" in message
    }


    private fun ServerConfiguration.legacyDeviceLinkUrl(): String =
        if (isCustom) "${backendUrl.trimEnd('/')}/tv-login" else "https://nuvio.tv/tv-login"

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
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class LegacyDeviceLinkStartResponse(
    val code: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
private data class StartSessionResult(
    val session: DeviceLinkStartResponse,
    val accessToken: String,
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
