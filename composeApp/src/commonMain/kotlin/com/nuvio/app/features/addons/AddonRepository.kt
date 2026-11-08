package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.concurrency.runCatchingUnlessCancelled
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var pulledFromServer = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val activeRefreshJobsLock = SynchronizedObject()
    private val profileOperationGate = AddonProfileOperationGate()
    private val pushJobsLock = SynchronizedObject()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun initialize() {
        val activeProfileId = ProfileRepository.activeProfileId
        val operation = activateProfile(activeProfileId) ?: return
        val shouldLoad = readIfCurrent(operation) {
            if (initialized) {
                false
            } else {
                initialized = true
                currentProfileId = operation.profileId
                true
            }
        }
        if (!shouldLoad) return

        log.d { "initialize() — loading local addons for profile ${operation.profileId}" }
        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(operation.profileId))
        val enabledByUrl = loadLocalEnabledStates(operation.profileId)
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        InAppLogger.info("Addons/Repository", "initialize profile=${operation.profileId} localAddons=${storedUrls.size}")

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        val applied = runIfCurrent(operation) {
            _uiState.value = if (storedUrls.isEmpty()) {
                AddonsUiState()
            } else {
                AddonsUiState(
                    addons = storedUrls.map { manifestUrl ->
                        existingByUrl[manifestUrl].toPendingAddon(
                            manifestUrl = manifestUrl,
                            enabled = enabledByUrl[manifestUrl],
                        )
                    },
                )
            }
        }
        if (!applied || storedUrls.isEmpty()) return

        storedUrls.forEach { manifestUrl ->
            if (!isCurrentOperation(operation)) return@forEach
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl, operation)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (profileOperationGate.current(effectiveProfileId) != null && initialized) return
        InAppLogger.info(
            "Addons/Repository",
            "profile changed requested=$profileId effective=$effectiveProfileId previous=$currentProfileId",
        )
        profileOperationGate.invalidate(effectiveProfileId) {
            currentProfileId = effectiveProfileId
            initialized = false
            pulledFromServer = false
            _uiState.value = AddonsUiState()
        }
        cancelActiveRefreshes()
    }

    fun clearLocalState() {
        InAppLogger.info("Addons/Repository", "clear local in-memory state")
        profileOperationGate.invalidate(1) {
            currentProfileId = 1
            initialized = false
            pulledFromServer = false
            _uiState.value = AddonsUiState()
        }
        cancelActiveRefreshes()
        val pushJobs = synchronized(pushJobsLock) {
            pushJobsByProfile.values.toList().also { pushJobsByProfile.clear() }
        }
        pushJobs.forEach(Job::cancel)
    }

    suspend fun pullFromServer(profileId: Int) {
        if (profileId != ProfileRepository.activeProfileId) return
        val operation = activateProfile(profileId) ?: return
        val client = SupabaseProvider.client
        val wasAlreadyPulled = readIfCurrent(operation) { pulledFromServer } ?: return
        log.i {
            "pullFromServer() — profileId=${operation.profileId}, initialized=${initialized}, " +
                "pulledFromServer=$wasAlreadyPulled"
        }
        InAppLogger.info(
            "Addons/Repository",
            "pullFromServer profile=${operation.profileId} initialized=$initialized pulled=$wasAlreadyPulled",
        )

        runCatchingUnlessCancelled {
            val rows = client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", operation.profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()
            if (!isCurrentOperation(operation)) return@runCatchingUnlessCancelled

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val urls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            InAppLogger.info("Addons/Repository", "pullFromServer serverRows=${rows.size} uniqueUrls=${urls.size}")
            urls.forEachIndexed { i, url -> log.d { "  server[$i]: $url" } }

            if (urls.isEmpty() && !wasAlreadyPulled) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(operation.profileId))
                if (!isCurrentOperation(operation)) return@runCatchingUnlessCancelled
                log.i { "pullFromServer() — server empty, local has ${localUrls.size} addons" }
                if (localUrls.isNotEmpty()) {
                    initialize()
                    if (!isCurrentOperation(operation)) return@runCatchingUnlessCancelled

                    val enabledByUrl = loadLocalEnabledStates(operation.profileId)
                    val localState = _uiState.value
                    val addons = localUrls.mapIndexed { index, addonUrl ->
                        val manifestUrl = ensureManifestSuffix(addonUrl)
                        AddonPushItem(
                            url = manifestUrl,
                            name = localState.addons.find { it.manifestUrl == manifestUrl }?.manifest?.name.orEmpty(),
                            enabled = enabledByUrl[manifestUrl]
                                ?: localState.addons.find { it.manifestUrl == manifestUrl }?.enabled
                                ?: true,
                            sortOrder = index,
                        )
                    }
                    val params = buildJsonObject {
                        put("p_profile_id", operation.profileId)
                        put("p_addons", json.encodeToJsonElement(addons))
                        putSyncOriginClientId()
                    }
                    client.postgrest.rpc("sync_push_addons", params)
                    runIfCurrent(operation) { pulledFromServer = true }
                    log.i { "pullFromServer() — migration push done (${addons.size} addons)" }
                    InAppLogger.info("Addons/Repository", "migrated local addons to server count=${addons.size}")
                    return@runCatchingUnlessCancelled
                }
            }

            if (urls.isEmpty()) {
                val localUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(operation.profileId))
                if (!isCurrentOperation(operation)) return@runCatchingUnlessCancelled
                if (localUrls.isNotEmpty()) {
                    log.w {
                        "pullFromServer() — remote empty while local has ${localUrls.size} addons; preserving local addons"
                    }
                    val enabledByUrl = loadLocalEnabledStates(operation.profileId)
                    val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
                    val nextState = AddonsUiState(
                        addons = localUrls.map { url ->
                            existingByUrl[url].toPendingAddon(
                                manifestUrl = url,
                                enabled = enabledByUrl[url],
                            )
                        },
                    )
                    val applied = runIfCurrent(operation) {
                        _uiState.value = nextState
                        persistSnapshot(operation.profileId)
                        pulledFromServer = true
                        initialized = true
                    }
                    if (!applied) return@runCatchingUnlessCancelled
                    localUrls.forEach { url ->
                        if (!isCurrentOperation(operation)) return@forEach
                        val existing = existingByUrl[url]
                        val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
                        if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                            refreshAddon(url, operation)
                        }
                    }
                    return@runCatchingUnlessCancelled
                }
            }

            val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            val nextState = AddonsUiState(
                addons = urls.map { url ->
                    val row = rowsByUrl[url]
                    existingByUrl[url].toPendingAddon(
                        manifestUrl = url,
                        userSetName = row?.name?.takeIf { it.isNotBlank() },
                        enabled = row?.enabled,
                    )
                },
            )
            val applied = runIfCurrent(operation) {
                _uiState.value = nextState
                persistSnapshot(operation.profileId)
                pulledFromServer = true
                initialized = true
            }
            if (!applied) return@runCatchingUnlessCancelled

            urls.forEach { url ->
                if (!isCurrentOperation(operation)) return@forEach
                val existing = existingByUrl[url]
                val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
                if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                    refreshAddon(url, operation)
                }
            }
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
            InAppLogger.info("Addons/Repository", "pullFromServer applied=${urls.size}")
        }.onFailure { error ->
            log.e(error) { "pullFromServer() — FAILED" }
            InAppLogger.error("Addons/Repository", "pullFromServer failed: ${InAppLogger.throwableSummary(error)}")
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        initialize()
        val operation = currentProfileOperation()
            ?: throw CancellationException("Add-on profile changed before installation started")
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "addAddon blocked because active profile uses primary addons")
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        InAppLogger.info("Addons/Repository", "addAddon rawUrl=${InAppLogger.redactUrl(rawUrl)}")
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            InAppLogger.warn(
                "Addons/Repository",
                "addAddon invalid url=${InAppLogger.redactUrl(rawUrl)} error=${error.message.orEmpty()}",
            )
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }
        InAppLogger.debug("Addons/Repository", "addAddon normalized=${InAppLogger.redactUrl(manifestUrl)}")

        val alreadyInstalled = readIfCurrent(operation) {
            _uiState.value.addons.any { it.manifestUrl == manifestUrl }
        } ?: throw CancellationException("Add-on profile changed before installation started")
        if (alreadyInstalled) {
            InAppLogger.warn("Addons/Repository", "addAddon duplicate url=${InAppLogger.redactUrl(manifestUrl)}")
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} reason=add")
                val payload = fetchAddonResponseText(manifestUrl)
                InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} ok chars=${payload.length} reason=add")
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InAppLogger.error(
                "Addons/Manifest",
                "GET ${InAppLogger.redactUrl(manifestUrl)} failed reason=add: ${InAppLogger.throwableSummary(error)}",
            )
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        val applied = runIfCurrent(operation) {
            _uiState.update { current ->
                current.copy(
                    addons = current.addons + ManagedAddon(
                        manifestUrl = manifestUrl,
                        manifest = manifest,
                        isRefreshing = false,
                        errorMessage = null,
                    ),
                )
            }
            persistSnapshot(operation.profileId)
        }
        if (!applied) throw CancellationException("Add-on profile changed while installing")
        pushToServer(operation)
        InAppLogger.info("Addons/Repository", "addAddon success name=${manifest.name} url=${InAppLogger.redactUrl(manifestUrl)}")
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        initialize()
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "removeAddon blocked because active profile uses primary addons")
            return
        }
        val operation = currentProfileOperation() ?: return
        log.i { "removeAddon() — $manifestUrl" }
        var changed = false
        val applied = runIfCurrent(operation) {
            _uiState.update { current ->
                val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
                changed = updatedAddons.size != current.addons.size
                if (changed) current.copy(addons = updatedAddons) else current
            }
            if (changed) persistSnapshot(operation.profileId)
        }
        if (!applied || !changed) return
        InAppLogger.info("Addons/Repository", "removeAddon removed=$changed url=${InAppLogger.redactUrl(manifestUrl)}")
        pushToServer(operation)
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        initialize()
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "moveAddon blocked because active profile uses primary addons")
            return
        }
        val operation = currentProfileOperation() ?: return
        var movedUrl: String? = null
        var changed = false
        val applied = runIfCurrent(operation) {
            _uiState.update { current ->
                val addons = current.addons
                if (
                    fromIndex !in addons.indices ||
                    toIndex !in addons.indices ||
                    fromIndex == toIndex
                ) {
                    InAppLogger.debug(
                        "Addons/Repository",
                        "moveAddon ignored from=$fromIndex to=$toIndex count=${addons.size}",
                    )
                    return@update current
                }

                val reordered = addons.toMutableList()
                val movingAddon = reordered.removeAt(fromIndex)
                movedUrl = movingAddon.manifestUrl
                reordered.add(toIndex, movingAddon)
                changed = true
                current.copy(addons = reordered)
            }
            if (changed) persistSnapshot(operation.profileId)
        }
        if (!applied || !changed) return
        movedUrl?.let { url ->
            InAppLogger.info("Addons/Repository", "moveAddon from=$fromIndex to=$toIndex url=${InAppLogger.redactUrl(url)}")
        }
        pushToServer(operation)
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        initialize()
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            InAppLogger.warn("Addons/Repository", "setAddonEnabled blocked because active profile uses primary addons")
            return
        }
        val operation = currentProfileOperation() ?: return
        var changed = false
        var shouldRefresh = false
        val applied = runIfCurrent(operation) {
            _uiState.update { current ->
                current.copy(
                    addons = current.addons.map { addon ->
                        if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                            addon
                        } else {
                            changed = true
                            shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                            addon.copy(enabled = enabled)
                        }
                    },
                )
            }
            if (changed) persistSnapshot(operation.profileId)
        }
        InAppLogger.info(
            "Addons/Repository",
            "setAddonEnabled enabled=$enabled changed=$changed shouldRefresh=$shouldRefresh url=${InAppLogger.redactUrl(manifestUrl)}",
        )
        if (!applied || !changed) return
        pushToServer(operation)
        if (shouldRefresh) {
            refreshAddon(manifestUrl, operation)
        }
    }

    fun refreshAll() {
        initialize()
        val operation = currentProfileOperation() ?: return
        val enabledAddons = _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }
        InAppLogger.info("Addons/Repository", "refreshAll enabledCount=${enabledAddons.size}")
        enabledAddons.forEach { addon ->
            if (!isCurrentOperation(operation)) return@forEach
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                operation = operation,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        initialize()
        val operation = currentProfileOperation() ?: return
        refreshAddon(manifestUrl, operation, forceRefresh)
    }

    private fun refreshAddon(
        manifestUrl: String,
        operation: AddonProfileOperation,
        forceRefresh: Boolean = false,
    ) {
        var refreshJob: Job? = null
        val scheduled = runIfCurrent(operation) {
            synchronized(activeRefreshJobsLock) {
                val existingJob = activeRefreshJobs[manifestUrl]
                if (existingJob?.isActive == true) {
                    InAppLogger.debug("Addons/Manifest", "refresh skipped active url=${InAppLogger.redactUrl(manifestUrl)}")
                } else {
                    markRefreshing(manifestUrl)
                    refreshJob = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val result = runCatchingUnlessCancelled {
                                InAppLogger.info("Addons/Manifest", "GET ${InAppLogger.redactUrl(manifestUrl)} reason=refresh")
                                val payload = fetchAddonResponseText(
                                    url = manifestUrl,
                                    forceRefresh = forceRefresh,
                                )
                                InAppLogger.info(
                                    "Addons/Manifest",
                                    "GET ${InAppLogger.redactUrl(manifestUrl)} ok chars=${payload.length} reason=refresh",
                                )
                                AddonManifestParser.parse(
                                    manifestUrl = manifestUrl,
                                    payload = payload,
                                )
                            }

                            runIfCurrent(operation) {
                                _uiState.update { current ->
                                    current.copy(
                                        addons = current.addons.map { addon ->
                                            if (addon.manifestUrl != manifestUrl) {
                                                addon
                                            } else {
                                                result.fold(
                                                    onSuccess = { manifest ->
                                                        InAppLogger.info(
                                                            "Addons/Manifest",
                                                            "refresh success name=${manifest.name} url=${InAppLogger.redactUrl(manifestUrl)}",
                                                        )
                                                        addon.copy(
                                                            manifest = manifest,
                                                            isRefreshing = false,
                                                            errorMessage = null,
                                                        )
                                                    },
                                                    onFailure = { error ->
                                                        InAppLogger.error(
                                                            "Addons/Manifest",
                                                            "refresh failed url=${InAppLogger.redactUrl(manifestUrl)}: ${InAppLogger.throwableSummary(error)}",
                                                        )
                                                        addon.copy(
                                                            isRefreshing = false,
                                                            errorMessage = error.message
                                                                ?: getString(Res.string.addon_load_manifest_failed),
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        } finally {
                            synchronized(activeRefreshJobsLock) {
                                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                                    activeRefreshJobs.remove(manifestUrl)
                                }
                            }
                        }
                    }
                    activeRefreshJobs[manifestUrl] = refreshJob!!
                }
            }
        }
        if (scheduled) refreshJob?.start()
    }

    private fun pushToServer(operation: AddonProfileOperation) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var addons: List<AddonPushItem>? = null
        val captured = runIfCurrent(operation) {
            addons = _uiState.value.addons
                .distinctBy { it.manifestUrl }
                .mapIndexed { index, addon ->
                    AddonPushItem(
                        url = addon.manifestUrl,
                        name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                        enabled = addon.enabled,
                        sortOrder = index,
                    )
                }
        }
        val snapshot = addons ?: return
        if (!captured) return

        // Capture the client before the debounce: a backend switch must never redirect
        // an old profile snapshot to the newly selected server.
        val client = SupabaseProvider.client
        val profileId = operation.profileId
        var pushJob: Job? = null
        pushJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                log.d { "pushToServer() — profileId=$profileId, pushing ${snapshot.size} addons" }
                InAppLogger.debug("Addons/Repository", "pushToServer profile=$profileId count=${snapshot.size}")
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(snapshot))
                    putSyncOriginClientId()
                }
                client.postgrest.rpc("sync_push_addons", params)
                log.d { "pushToServer() — success" }
                InAppLogger.info("Addons/Repository", "pushToServer success count=${snapshot.size}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
                InAppLogger.error("Addons/Repository", "pushToServer failed: ${InAppLogger.throwableSummary(error)}")
            } finally {
                synchronized(pushJobsLock) {
                    if (pushJobsByProfile[profileId] === pushJob) {
                        pushJobsByProfile.remove(profileId)
                    }
                }
            }
        }
        val previous = synchronized(pushJobsLock) {
            pushJobsByProfile.put(profileId, pushJob!!)
        }
        previous?.cancel()
        pushJob!!.start()
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persistSnapshot(profileId: Int) {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            profileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            profileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(profileId: Int): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun activateProfile(requestedProfileId: Int): AddonProfileOperation? {
        if (requestedProfileId != ProfileRepository.activeProfileId) return null
        val effectiveProfileId = resolveEffectiveProfileId(requestedProfileId)
        profileOperationGate.current(effectiveProfileId)?.let { return it }
        cancelActiveRefreshes()
        return profileOperationGate.invalidate(effectiveProfileId) {
            currentProfileId = effectiveProfileId
            initialized = false
            pulledFromServer = false
            _uiState.value = AddonsUiState()
        }
    }

    private fun <T> readIfCurrent(
        operation: AddonProfileOperation,
        block: () -> T,
    ): T? {
        var result: T? = null
        if (!runIfCurrent(operation) {
                result = block()
            }
        ) {
            return null
        }
        return result
    }

    private fun currentProfileOperation(): AddonProfileOperation? {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        return profileOperationGate.current(effectiveProfileId)
    }

    private fun isCurrentOperation(operation: AddonProfileOperation): Boolean =
        resolveEffectiveProfileId(ProfileRepository.activeProfileId) == operation.profileId &&
            profileOperationGate.isCurrent(operation)

    private fun runIfCurrent(
        operation: AddonProfileOperation,
        block: () -> Unit,
    ): Boolean = profileOperationGate.runIfCurrent(operation) {
        if (resolveEffectiveProfileId(ProfileRepository.activeProfileId) == operation.profileId) {
            block()
        }
    }

    private fun cancelActiveRefreshes() {
        val jobs = synchronized(activeRefreshJobsLock) {
            activeRefreshJobs.values.toList().also { activeRefreshJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
