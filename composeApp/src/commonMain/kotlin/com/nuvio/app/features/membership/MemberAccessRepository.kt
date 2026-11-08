package com.nuvio.app.features.membership

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val VerificationIntervalMs = 15L * 60L * 1_000L
private val RetryDelaysMs = listOf(1_000L, 2_000L, 4_000L)

object MemberAccessRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("MemberAccessRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshGeneration = MutableStateFlow(0L)
    private val accessLock = SynchronizedObject()
    private var accessGeneration = 0L
    private var accountCleanupDepth = 0
    private var accountCleanupInProgress = false
    private var blockedUserIdAfterCleanup: String? = null
    private var sawUnauthenticatedDuringCleanup = false
    private val _access = MutableStateFlow(MemberAccess.None)
    val access: StateFlow<MemberAccess> = _access.asStateFlow()
    private var started = false
    private var verifiedUserId: String? = null
    private var verifiedAtMs = 0L

    fun ensureStarted() {
        if (started) return
        started = true
        hydrateCachedAccess()
        scope.launch {
            combine(AuthRepository.state, refreshGeneration) { auth, _ -> auth }
                .collectLatest(::loadAccess)
        }
        scope.launch {
            while (true) {
                delay(VerificationIntervalMs)
                refreshIfStale()
            }
        }
    }

    fun refresh() {
        ensureStarted()
        refreshGeneration.value += 1L
    }

    fun refreshIfStale() {
        ensureStarted()
        val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return
        if (auth.isAnonymous) return
        val now = EpisodeReleaseDatePlatform.nowEpochMs()
        val shouldRefresh = synchronized(accessLock) {
            !accountCleanupInProgress &&
                blockedUserIdAfterCleanup != auth.userId &&
                isCurrentUser(auth.userId) &&
                (verifiedUserId != auth.userId || now - verifiedAtMs >= VerificationIntervalMs)
        }
        if (shouldRefresh) refresh()
    }

    fun clearLocalState() {
        synchronized(accessLock) {
            if (accountCleanupDepth == 0) {
                val currentUser = AuthRepository.state.value as? AuthState.Authenticated
                blockedUserIdAfterCleanup = currentUser?.userId
                sawUnauthenticatedDuringCleanup = false
            }
            accountCleanupDepth++
            accountCleanupInProgress = true
            accessGeneration++
            _access.value = MemberAccess.None
            verifiedUserId = null
            verifiedAtMs = 0L
            MemberAssetStorage.clearAccess()
        }
        ProfileBackgroundRepository.invalidate()
    }

    fun onAccountCleanupFinished() {
        val shouldRefresh = synchronized(accessLock) {
            if (accountCleanupDepth == 0) {
                return@synchronized false
            }
            accountCleanupDepth--
            if (accountCleanupDepth > 0) {
                return@synchronized false
            }

            accountCleanupInProgress = false
            val previousUserId = blockedUserIdAfterCleanup
            val currentUser = AuthRepository.state.value as? AuthState.Authenticated
            val switchedAccounts = currentUser != null && currentUser.userId != previousUserId
            val sameAccountReauthenticated = currentUser != null &&
                currentUser.userId == previousUserId &&
                sawUnauthenticatedDuringCleanup
            sawUnauthenticatedDuringCleanup = false
            if (currentUser == null || switchedAccounts || sameAccountReauthenticated) {
                blockedUserIdAfterCleanup = null
            }
            (switchedAccounts || sameAccountReauthenticated) && currentUser?.isAnonymous == false
        }
        if (shouldRefresh) refresh()
    }

    private suspend fun loadAccess(auth: AuthState) {
        when (auth) {
            AuthState.Loading -> return
            AuthState.Unauthenticated -> {
                synchronized(accessLock) {
                    if (accountCleanupInProgress) {
                        sawUnauthenticatedDuringCleanup = true
                    } else {
                        blockedUserIdAfterCleanup = null
                    }
                    _access.value = MemberAccess.None
                    verifiedUserId = null
                    verifiedAtMs = 0L
                }
                return
            }
            is AuthState.Authenticated -> {
                if (auth.isAnonymous) {
                    synchronized(accessLock) {
                        if (!accountCleanupInProgress) blockedUserIdAfterCleanup = null
                        _access.value = MemberAccess.None
                        verifiedUserId = null
                        verifiedAtMs = 0L
                    }
                    return
                }
            }
        }

        val account = auth as AuthState.Authenticated
        val generation = synchronized(accessLock) {
            if (
                !accountCleanupInProgress &&
                blockedUserIdAfterCleanup != account.userId &&
                isCurrentUser(account.userId)
            ) {
                if (blockedUserIdAfterCleanup != null && blockedUserIdAfterCleanup != account.userId) {
                    blockedUserIdAfterCleanup = null
                }
                accessGeneration
            } else {
                null
            }
        } ?: return

        val cached = loadCached(account.userId) ?: MemberAccess.None
        val cachePublished = synchronized(accessLock) {
            if (!canPublish(account.userId, generation)) {
                false
            } else {
                _access.value = cached
                warmMemberAssets(cached)
                true
            }
        }
        if (!cachePublished) return

        val remote = fetchWithRetry() ?: return
        synchronized(accessLock) {
            if (canPublish(account.userId, generation)) {
                val effective = saveRemote(account.userId, remote)
                _access.value = effective
                warmMemberAssets(effective)
                verifiedUserId = account.userId
                verifiedAtMs = EpisodeReleaseDatePlatform.nowEpochMs()
            }
        }
    }

    private fun isCurrentUser(userId: String): Boolean =
        (AuthRepository.state.value as? AuthState.Authenticated)?.let { auth ->
            !auth.isAnonymous && auth.userId == userId
        } == true

    private fun canPublish(userId: String, generation: Long): Boolean =
        canPublishMemberAccess(
            authState = AuthRepository.state.value,
            userId = userId,
            generation = generation,
            currentGeneration = accessGeneration,
            accountCleanupInProgress = accountCleanupInProgress,
            blockedUserIdAfterCleanup = blockedUserIdAfterCleanup,
        )

    private suspend fun fetchWithRetry(): MemberAccess? {
        repeat(RetryDelaysMs.size + 1) { attempt ->
            try {
                return MemberAccessRemoteDataSource.getMemberAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt == RetryDelaysMs.size) {
                    log.w(error) { "Unable to verify supporter access; retaining cached access" }
                    return null
                }
                delay(RetryDelaysMs[attempt])
            }
        }
        return null
    }

    private fun loadCached(userId: String): MemberAccess? {
        val stored = loadStoredAccess() ?: return null
        if (stored.userId != userId) return null
        return stored.toMemberAccess()
    }

    private fun hydrateCachedAccess() {
        val auth = AuthRepository.state.value
        val account = auth as? AuthState.Authenticated ?: return
        if (!canHydrateMemberAccess(auth, account.userId)) return

        val generation = synchronized(accessLock) {
            if (
                !accountCleanupInProgress &&
                blockedUserIdAfterCleanup != account.userId &&
                isCurrentUser(account.userId)
            ) {
                accessGeneration
            } else {
                null
            }
        } ?: return
        val stored = loadStoredAccess() ?: return
        if (!canHydrateMemberAccess(auth, stored.userId)) return

        val cached = stored.toMemberAccess()
        synchronized(accessLock) {
            if (canPublish(account.userId, generation)) {
                _access.value = cached
                warmMemberAssets(cached)
            }
        }
    }

    private fun loadStoredAccess(): StoredMemberAccess? {
        val payload = MemberAssetStorage.loadAccessPayload() ?: return null
        return runCatching { json.decodeFromString<StoredMemberAccess>(payload) }.getOrNull()
    }

    private fun StoredMemberAccess.toMemberAccess(): MemberAccess {
        val tier = this.tier?.let { name -> MemberTier.entries.firstOrNull { it.name == name } }
            ?: return MemberAccess.None
        val decodedEntitlements = this.entitlements
            .mapNotNull { name -> CosmeticEntitlement.entries.firstOrNull { it.name == name } }
            .toSet()
        return MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements(decodedEntitlements),
        )
    }

    private fun warmMemberAssets(access: MemberAccess) {
        if (access.entitlements.includes(CosmeticEntitlement.PROFILE_BACKGROUNDS)) {
            ProfileBackgroundRepository.ensureLoaded()
        }
    }

    private fun saveRemote(userId: String, remote: MemberAccess): MemberAccess {
        MemberAssetStorage.saveAccessPayload(
            json.encodeToString(
                StoredMemberAccess(
                    userId = userId,
                    tier = remote.tier?.name,
                    entitlements = remote.entitlements.names(),
                ),
            ),
        )
        return remote
    }
}

internal fun canHydrateMemberAccess(authState: AuthState, storedUserId: String): Boolean =
    (authState as? AuthState.Authenticated)?.let { auth ->
        !auth.isAnonymous && auth.userId == storedUserId
    } == true

internal fun canPublishMemberAccess(
    authState: AuthState,
    userId: String,
    generation: Long,
    currentGeneration: Long,
    accountCleanupInProgress: Boolean = false,
    blockedUserIdAfterCleanup: String? = null,
): Boolean =
    !accountCleanupInProgress &&
        blockedUserIdAfterCleanup != userId &&
        generation == currentGeneration &&
        canHydrateMemberAccess(authState, userId)

@Serializable
private data class StoredMemberAccess(
    val userId: String,
    val tier: String? = null,
    val entitlements: Set<String> = emptySet(),
)
