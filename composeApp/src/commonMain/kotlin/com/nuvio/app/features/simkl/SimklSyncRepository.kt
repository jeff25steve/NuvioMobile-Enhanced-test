package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProfileStore
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklSyncRepository : TrackingProfileStore {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    private val log = Logger.withTag("SimklSync")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshGate = SimklRefreshGate()
    private val snapshotMutex = Mutex()
    private val profileStateLock = SynchronizedObject()
    private val refreshRequestSequence = atomic(0L)
    private val engine = SimklSyncEngine(
        remote = SimklApiSyncRemote(),
        nowEpochMs = SimklPlatformClock::nowEpochMs,
    )

    private val _state = MutableStateFlow(SimklSyncUiState())
    val state: StateFlow<SimklSyncUiState> = _state.asStateFlow()

    private var hasLoaded = false
    private var profileGeneration = 0L

    init {
        TrackingProviderRegistry.registerProfileStore(this)
    }

    internal fun captureProfileScope(expectedProfileId: Int? = null): SimklSyncProfileScope? =
        synchronized(profileStateLock) {
            val profileId = ProfileRepository.activeProfileId
            if (expectedProfileId != null && expectedProfileId != profileId) {
                return@synchronized null
            }
            SimklSyncProfileScope(profileId = profileId, generation = profileGeneration)
        }

    internal fun isProfileScopeCurrent(profileScope: SimklSyncProfileScope): Boolean =
        synchronized(profileStateLock) {
            isProfileScopeCurrentLocked(profileScope)
        }

    private fun isProfileScopeCurrentLocked(profileScope: SimklSyncProfileScope): Boolean =
        isSimklSyncProfileScopeCurrent(
            scope = profileScope,
            currentGeneration = profileGeneration,
            activeProfileId = ProfileRepository.activeProfileId,
        )

    fun ensureLoaded() {
        val profileScope = synchronized(profileStateLock) {
            if (hasLoaded) return
            hasLoaded = true
            SimklSyncProfileScope(
                profileId = ProfileRepository.activeProfileId,
                generation = profileGeneration,
            )
        }
        val snapshot = SimklSyncStorage.loadPayload(profileScope.profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklSyncSnapshot>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl sync snapshot: ${error.message}" } }
                    .getOrNull()
            }
            ?.withoutLocalPlaceholders()
            ?: SimklSyncSnapshot()
        val committed = synchronized(profileStateLock) {
            if (!isProfileScopeCurrentLocked(profileScope)) {
                false
            } else {
                _state.value = SimklSyncUiState(snapshot = snapshot, hasLoaded = true)
                true
            }
        }
        if (committed) SimklWatchDiagnostics.logSnapshot(stage = "cache-load", snapshot = snapshot)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        refreshAsync(intent, SimklRefreshOrigin.UNKNOWN)
    }

    internal fun refreshAsync(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ) {
        scope.launch { refresh(intent, origin) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent): Boolean =
        refresh(intent, SimklRefreshOrigin.MANUAL_SYNC)

    internal suspend fun refresh(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ): Boolean {
        ensureLoaded()
        val profileScope = captureProfileScope() ?: return false
        val requestId = refreshRequestSequence.incrementAndGet()
        val before = _state.value
        SimklWatchDiagnostics.logRefreshRequest(
            requestId = requestId,
            origin = origin,
            intent = intent,
            profileId = profileScope.profileId,
            profileGeneration = profileScope.generation,
            authenticated = SimklAuthRepository.isAuthenticated.value,
            snapshot = before.snapshot,
            errorMessage = before.errorMessage,
        )
        val outcome = refreshGate.runIfNeeded(
            profileGeneration = profileScope.generation,
            shouldRun = {
                val current = _state.value
                val authenticated = SimklAuthRepository.isAuthenticated.value
                val nowEpochMs = SimklPlatformClock.nowEpochMs()
                val eligible = isProfileScopeCurrent(profileScope) &&
                    authenticated &&
                    shouldRunSimklRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = nowEpochMs,
                        hasError = current.errorMessage != null,
                    )
                SimklWatchDiagnostics.logRefreshDecision(
                    requestId = requestId,
                    origin = origin,
                    intent = intent,
                    nowEpochMs = nowEpochMs,
                    lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                    authenticated = authenticated,
                    hasError = current.errorMessage != null,
                    eligible = eligible,
                )
                eligible
            },
        ) {
            refreshSnapshot(profileScope)
        }
        SimklWatchDiagnostics.logRefreshCompletion(
            requestId = requestId,
            origin = origin,
            intent = intent,
            outcome = outcome,
            before = before,
            after = _state.value,
        )
        val completed = _state.value
        return isProfileScopeCurrent(profileScope) &&
            SimklAuthRepository.isAuthenticated.value &&
            completed.hasLoaded &&
            completed.errorMessage == null
    }

    private suspend fun refreshSnapshot(profileScope: SimklSyncProfileScope) = snapshotMutex.withLock {
        val previous = synchronized(profileStateLock) {
            if (!isProfileScopeCurrentLocked(profileScope)) {
                null
            } else {
                _state.value.also { current ->
                    _state.value = current.copy(isLoading = true, errorMessage = null)
                }
            }
        } ?: return@withLock

        val result = try {
            engine.synchronize(previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl sync failed: ${error.message}" }
            synchronized(profileStateLock) {
                if (isProfileScopeCurrentLocked(profileScope)) {
                    _state.value = previous.copy(
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = error.message ?: "Unable to sync Simkl",
                    )
                }
            }
            return@withLock
        }

        if (!isProfileScopeCurrent(profileScope)) return@withLock
        SimklAuthRepository.synchronizeUserSettings(result.activities?.settings?.all)
        val committed = synchronized(profileStateLock) {
            if (!isProfileScopeCurrentLocked(profileScope)) {
                false
            } else {
                SimklSyncStorage.savePayload(profileScope.profileId, json.encodeToString(result))
                _state.value = SimklSyncUiState(
                    snapshot = result,
                    hasLoaded = true,
                )
                true
            }
        }
        if (committed) SimklWatchDiagnostics.logSnapshot(stage = "network-commit", snapshot = result)
    }

    internal suspend fun commitPlaybackRemoval(
        sessionIds: Set<Long>,
        profileScope: SimklSyncProfileScope,
    ) {
        if (sessionIds.isEmpty()) return
        ensureLoaded()
        snapshotMutex.withLock {
            val current = synchronized(profileStateLock) {
                if (isProfileScopeCurrentLocked(profileScope)) _state.value else null
            } ?: return@withLock
            val updatedPlayback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (updatedPlayback.size == current.snapshot.playback.size) return@withLock
            val updatedSnapshot = current.snapshot.copy(playback = updatedPlayback)
            val committed = synchronized(profileStateLock) {
                if (!isProfileScopeCurrentLocked(profileScope)) {
                    false
                } else {
                    SimklSyncStorage.savePayload(profileScope.profileId, json.encodeToString(updatedSnapshot))
                    _state.value = current.copy(snapshot = updatedSnapshot)
                    true
                }
            }
            if (committed) SimklWatchDiagnostics.logSnapshot(stage = "playback-removal", snapshot = updatedSnapshot)
        }
    }

    internal suspend fun commitScrobble(
        result: SimklScrobbleResult,
        profileScope: SimklSyncProfileScope,
    ): Boolean {
        ensureLoaded()
        snapshotMutex.withLock {
            val current = synchronized(profileStateLock) {
                if (isProfileScopeCurrentLocked(profileScope)) _state.value else null
            } ?: return@withLock false
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock false
            val committed = synchronized(profileStateLock) {
                if (!isProfileScopeCurrentLocked(profileScope)) {
                    false
                } else {
                    SimklSyncStorage.savePayload(profileScope.profileId, json.encodeToString(snapshot))
                    _state.value = current.copy(snapshot = snapshot)
                    true
                }
            }
            if (committed) {
                SimklWatchDiagnostics.logSnapshot(stage = "scrobble-commit", snapshot = snapshot)
                refreshIfHoldingLocalEntries(snapshot)
            }
            committed
        }
    }

    internal suspend fun commitMutation(
        receipt: SimklMutationReceipt,
        profileScope: SimklSyncProfileScope,
    ): Boolean {
        ensureLoaded()
        snapshotMutex.withLock {
            val current = synchronized(profileStateLock) {
                if (isProfileScopeCurrentLocked(profileScope)) _state.value else null
            } ?: return@withLock false
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock false
            val committed = synchronized(profileStateLock) {
                if (!isProfileScopeCurrentLocked(profileScope)) {
                    false
                } else {
                    SimklSyncStorage.savePayload(profileScope.profileId, json.encodeToString(snapshot))
                    _state.value = current.copy(snapshot = snapshot)
                    true
                }
            }
            if (committed) {
                SimklWatchDiagnostics.logSnapshot(stage = "mutation-commit", snapshot = snapshot)
                refreshIfHoldingLocalEntries(snapshot)
            }
            committed
        }
    }

    private fun refreshIfHoldingLocalEntries(snapshot: SimklSyncSnapshot) {
        if (snapshot.entries.none(SimklLibraryEntry::isLocalPlaceholder)) return
        refreshAsync(
            intent = TrackingRefreshIntent.INVALIDATED,
            origin = SimklRefreshOrigin.MUTATION,
        )
    }

    override fun onProfileChanged() {
        synchronized(profileStateLock) {
            profileGeneration += 1L
            hasLoaded = false
            _state.value = SimklSyncUiState()
        }
        ensureLoaded()
    }

    override fun clearLocalState() {
        val profileId = synchronized(profileStateLock) {
            val currentProfileId = ProfileRepository.activeProfileId
            profileGeneration += 1L
            hasLoaded = false
            _state.value = SimklSyncUiState()
            currentProfileId
        }
        SimklSyncStorage.savePayload(profileId, "")
    }

    /**
     * Bumps the projection version to force downstream collectors (CW, library, watched badges)
     * to recompute their projections without re-fetching from the network.
     * Call this when a setting that affects projection output changes (e.g. anime ID preference).
     */
    fun invalidateProjections() {
        synchronized(profileStateLock) {
            val current = _state.value
            _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
        }
    }

    override fun removeStoredProfile(profileId: Int) {
        synchronized(profileStateLock) {
            if (ProfileRepository.activeProfileId == profileId) {
                profileGeneration += 1L
                hasLoaded = false
                _state.value = SimklSyncUiState()
            }
        }
        SimklSyncStorage.removeProfile(profileId)
    }
}
