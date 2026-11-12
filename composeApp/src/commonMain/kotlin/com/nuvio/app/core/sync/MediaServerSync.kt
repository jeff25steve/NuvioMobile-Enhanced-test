package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthSessionIdentity
import com.nuvio.app.core.auth.isAuthSessionIdentityCurrentForProfile
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerSyncSnapshot
import com.nuvio.app.features.servers.SyncedServer
import com.nuvio.app.features.servers.mergeSyncedServers
import com.nuvio.app.features.servers.toSyncPayload
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MEDIA_SERVER_PUSH_DEBOUNCE_MS = 500L

@Serializable
private data class SupabaseMediaServers(
    @SerialName("servers_json") val servers: List<SyncedServer> = emptyList(),
)

private data class MediaServerSyncScope(
    val profileId: Int,
    val identity: AuthSessionIdentity,
)

object MediaServerSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("MediaServerSync")
    private val syncMutex = Mutex()
    private var observeJob: Job? = null

    @OptIn(FlowPreview::class)
    fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            ServerRepository.localChanges
                .debounce(MEDIA_SERVER_PUSH_DEBOUNCE_MS)
                .collect { profileId ->
                    try {
                        syncMutex.withLock { sync(profileId, pushOnly = true) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.e(error) { "Failed to push media servers for profile $profileId" }
                    }
                }
        }
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
    }

    suspend fun syncFromRemote(profileId: Int): Boolean = syncMutex.withLock {
        try {
            sync(profileId, pushOnly = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Media server sync failed for profile $profileId" }
            throw error
        }
    }

    private suspend fun sync(profileId: Int, pushOnly: Boolean): Boolean {
        val expectedScope = currentScope(profileId) ?: return false
        try {
            if (!isCurrentScope(expectedScope)) return false
            val local = ServerRepository.syncSnapshot(profileId) ?: return false
            if (!isCurrentScope(expectedScope)) return false

            if (local.syncedKeys != null && (pushOnly || local.pendingPush)) {
                if (local.pendingPush && !push(local, expectedScope)) return false
                return false
            }

            val remote = pull(expectedScope) ?: return false
            if (!isCurrentScope(expectedScope)) return false
            val merged = mergeSyncedServers(local.servers, remote, local.syncedKeys)
            val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
            var applied = false
            val identityCurrent = AuthRepository.runIfSessionIdentityCurrent(expectedScope.identity) {
                if (ProfileRepository.activeProfileId == expectedScope.profileId) {
                    applied = ServerRepository.applySync(local, merged, remoteKeys)
                }
            }
            if (!identityCurrent || !applied) return false

            if (merged.any { it.key !in remoteKeys }) {
                if (!isCurrentScope(expectedScope)) return false
                val currentSnapshot = ServerRepository.syncSnapshot(profileId) ?: return false
                if (!push(currentSnapshot, expectedScope)) return false
            }
            log.d { "Synchronized ${merged.size} media servers for profile $profileId" }
            return merged != local.servers
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AuthRepository.signOutIfSessionInvalid(error, "Media server sync", expectedScope.identity)
            throw error
        }
    }

    private suspend fun pull(expectedScope: MediaServerSyncScope): List<SyncedServer>? {
        if (!isCurrentScope(expectedScope)) return null
        val params = buildJsonObject { put("p_profile_id", expectedScope.profileId) }
        val servers = SupabaseProvider.client.postgrest
            .rpc("sync_pull_media_servers", params)
            .decodeList<SupabaseMediaServers>()
            .firstOrNull()
            ?.servers
        return servers.takeIf { isCurrentScope(expectedScope) }
    }

    private suspend fun push(snapshot: ServerSyncSnapshot, expectedScope: MediaServerSyncScope): Boolean {
        if (!isCurrentScope(expectedScope)) return false
        SupabaseProvider.client.postgrest.rpc(
            function = "sync_push_media_servers",
            parameters = buildJsonObject {
                put("p_profile_id", snapshot.profileId)
                put("p_servers", snapshot.servers.toSyncPayload())
                putSyncOriginClientId()
            },
        )
        if (!isCurrentScope(expectedScope)) return false
        var markedPushed = false
        val identityCurrent = AuthRepository.runIfSessionIdentityCurrent(expectedScope.identity) {
            if (ProfileRepository.activeProfileId == expectedScope.profileId) {
                ServerRepository.markPushed(snapshot)
                markedPushed = true
            }
        }
        if (!identityCurrent || !markedPushed) return false
        log.d { "Pushed ${snapshot.servers.size} media servers for profile ${snapshot.profileId}" }
        return true
    }

    private fun currentScope(profileId: Int): MediaServerSyncScope? {
        if (ProfileRepository.activeProfileId != profileId) return null
        val identity = AuthRepository.captureSessionIdentity()
            ?.takeIf { session -> !session.isAnonymous }
            ?: return null
        return MediaServerSyncScope(profileId = profileId, identity = identity)
    }

    private fun isCurrentScope(expectedScope: MediaServerSyncScope): Boolean =
        isAuthSessionIdentityCurrentForProfile(
            expectedProfileId = expectedScope.profileId,
            currentProfileId = ProfileRepository.activeProfileId,
            expectedIdentity = expectedScope.identity,
            currentIdentity = AuthRepository.captureSessionIdentity(),
        )
}
