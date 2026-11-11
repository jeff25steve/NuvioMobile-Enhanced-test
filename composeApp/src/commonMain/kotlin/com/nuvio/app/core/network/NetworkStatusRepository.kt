package com.nuvio.app.core.network

import androidx.compose.runtime.Composable
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.network_cannot_reach_servers
import nuvio.composeapp.generated.resources.network_connection_issue
import nuvio.composeapp.generated.resources.network_no_internet_connection
import nuvio.composeapp.generated.resources.network_please_check_connection
import org.jetbrains.compose.resources.stringResource

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

@Composable
fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.network_cannot_reach_servers)
        NetworkCondition.NoInternet -> stringResource(Res.string.network_no_internet_connection)
        else -> stringResource(Res.string.network_connection_issue)
    }

@Composable
fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
        NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
        else -> stringResource(Res.string.network_please_check_connection)
    }

object NetworkStatusRepository {
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val FOREGROUND_REFRESH_DELAY_MS = 6_000L
    private const val FOREGROUND_FAILURE_CONFIRM_DELAY_MS = 2_000L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private val refreshGate = NetworkRefreshGate()
    private var foregroundRefreshJob: Job? = null

    fun ensureStarted() {
        if (refreshGate.ensureStarted()) launchProbeLoop(confirmFailures = false)
    }

    fun requestForegroundRefresh() {
        ensureStarted()
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = scope.launch {
            delay(FOREGROUND_REFRESH_DELAY_MS)
            requestRefresh(force = true, confirmFailures = true)
        }
    }

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false) {
        if (refreshGate.request(force, confirmFailures)) {
            launchProbeLoop(confirmFailures)
        }
    }

    private fun launchProbeLoop(initialConfirmFailures: Boolean) {
        scope.launch {
            var nextConfirmFailures = initialConfirmFailures
            var ownsRefreshGate = true
            try {
                while (true) {
                    runProbe(confirmFailures = nextConfirmFailures)
                    val pendingConfirmFailures = refreshGate.finishProbe()
                    if (pendingConfirmFailures == null) {
                        ownsRefreshGate = false
                        return@launch
                    }
                    nextConfirmFailures = pendingConfirmFailures
                }
            } finally {
                if (ownsRefreshGate) refreshGate.abort()
            }
        }
    }

    private suspend fun runProbe(confirmFailures: Boolean) {
        if (_uiState.value.condition == NetworkCondition.Unknown) {
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.Checking)
        }

        val previousCondition = _uiState.value.condition
        var nextCondition = probeCondition()
        if (
            confirmFailures &&
            previousCondition == NetworkCondition.Online &&
            nextCondition.isOfflineLike()
        ) {
            delay(FOREGROUND_FAILURE_CONFIRM_DELAY_MS)
            nextCondition = probeCondition()
        }

        _uiState.value = NetworkStatusUiState(condition = nextCondition)
    }

    private suspend fun probeCondition(): NetworkCondition {
        val internetReachable = probePublicInternet()
        if (!internetReachable) {
            return NetworkCondition.NoInternet
        }

        val supabaseReachable = SupabaseEndpointConfig.restEndpointUrls().any { url ->
            probeReachable(
                url = url,
                headers = mapOf("apikey" to ServerConfigurationRepository.active.value.publishableKey),
            )
        }
        if (!supabaseReachable) {
            return NetworkCondition.ServersUnreachable
        }

        return NetworkCondition.Online
    }

    private suspend fun probePublicInternet(): Boolean =
        probeReachable(PUBLIC_PROBE_PRIMARY) || probeReachable(PUBLIC_PROBE_FALLBACK)

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }

    private fun NetworkCondition.isOfflineLike(): Boolean =
        this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable
}

/**
 * Thread-safe single-flight gate for the process-wide connectivity probe.
 *
 * At most one probe loop owns the gate. Forced refreshes requested while it
 * runs are coalesced into one follow-up, retaining failure confirmation if
 * any queued caller requested it.
 */
internal class NetworkRefreshGate {
    private val lock = SynchronizedObject()
    private var started = false
    private var probeInFlight = false
    private var pendingConfirmFailures: Boolean? = null

    fun ensureStarted(): Boolean = synchronized(lock) {
        if (started) return@synchronized false
        started = true
        probeInFlight = true
        true
    }

    fun request(force: Boolean, confirmFailures: Boolean): Boolean = synchronized(lock) {
        started = true
        if (probeInFlight) {
            if (force) {
                pendingConfirmFailures = (pendingConfirmFailures ?: false) || confirmFailures
            }
            false
        } else {
            probeInFlight = true
            true
        }
    }

    /** Returns null when the loop is finished, or the confirmation mode for its next pass. */
    fun finishProbe(): Boolean? = synchronized(lock) {
        val pending = pendingConfirmFailures
        if (pending == null) {
            probeInFlight = false
            null
        } else {
            pendingConfirmFailures = null
            pending
        }
    }

    fun abort() {
        synchronized(lock) {
            started = false
            probeInFlight = false
            pendingConfirmFailures = null
        }
    }
}
