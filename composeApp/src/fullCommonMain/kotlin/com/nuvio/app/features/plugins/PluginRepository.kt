package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.nuvio.app.core.concurrency.runCatchingUnlessCancelled
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_error_enter_repo_url
import nuvio.composeapp.generated.resources.plugins_error_enter_valid_url
import nuvio.composeapp.generated.resources.plugins_error_provider_not_found
import nuvio.composeapp.generated.resources.plugins_repository_already_installed
import nuvio.composeapp.generated.resources.plugins_repository_install_failed
import nuvio.composeapp.generated.resources.plugins_repository_refresh_failed
import org.jetbrains.compose.resources.getString

@Serializable
private data class PluginRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class PluginPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private data class PluginPersistenceSnapshot(
    val profileId: Int,
    val generation: Long,
    val revision: Long,
    val state: PluginsUiState,
)

private data class LoadedPluginState(
    val state: PluginsUiState,
    val requiresMigration: Boolean,
)

actual object PluginRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("PluginRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(PluginsUiState())
    actual val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val activeRefreshJobsLock = SynchronizedObject()
    private val profileOperationGate = PluginProfileOperationGate()
    private val pushJobsLock = SynchronizedObject()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()
    private val persistenceGeneration = atomic(0L)
    private val persistenceRevision = atomic(0L)
    private val persistenceLock = SynchronizedObject()
    private val persistedRevisionByProfile = mutableMapOf<Int, Long>()

    actual fun initialize() {
        val operation = activateProfile(ProfileRepository.activeProfileId) ?: return
        val shouldRefreshStoredRepos = readIfCurrent(operation) {
            !initialized || currentProfileId != operation.profileId
        } ?: return
        ensureStateLoadedForProfile(operation.profileId, operation)
        if (!shouldRefreshStoredRepos || !isCurrentOperation(operation)) return

        val currentState = readIfCurrent(operation) { _uiState.value } ?: return
        val nowEpochMs = currentEpochMillis()
        currentState.repositories.filter { repository ->
            shouldRefreshRepository(repository, currentState.scrapers, nowEpochMs)
        }.forEach { repository ->
            if (!isCurrentOperation(operation)) return@forEach
            refreshRepositoryInternal(
                repository.manifestUrl,
                pushAfterRefresh = false,
                ensureInitialized = false,
                operation = operation,
            )
        }
    }

    actual fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (profileOperationGate.current(effectiveProfileId) != null && initialized) return

        profileOperationGate.invalidate(effectiveProfileId) {
            cancelActiveRefreshes()
            cancelPushJobs()
            currentProfileId = effectiveProfileId
            initialized = false
            _uiState.value = PluginsUiState()
        }
    }

    actual fun clearLocalState() {
        profileOperationGate.invalidate(1) {
            cancelActiveRefreshes()
            cancelPushJobs()
            persistenceGeneration.incrementAndGet()
            synchronized(persistenceLock) {
                persistedRevisionByProfile.clear()
            }
            currentProfileId = 1
            initialized = false
            _uiState.value = PluginsUiState()
        }
    }

    actual suspend fun pullFromServer(profileId: Int) {
        val operation = activateProfile(profileId) ?: return
        ensureStateLoadedForProfile(operation.profileId, operation)
        val client = SupabaseProvider.client

        runCatchingUnlessCancelled {
            val rows = client.postgrest
                .from("plugins")
                .select {
                    filter { eq("profile_id", operation.profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<PluginRow>()
            if (!isCurrentOperation(operation)) return@runCatchingUnlessCancelled

            val urls = dedupeManifestUrls(rows.map { it.url })
            val existingState = readIfCurrent(operation) { _uiState.value }
                ?: return@runCatchingUnlessCancelled
            val existingReposByUrl = existingState.repositories.associateBy { it.manifestUrl }
            val nowEpochMs = currentEpochMillis()
            val nextRepos = urls.map { url ->
                val existing = existingReposByUrl[url]
                if (existing == null) {
                    PluginRepositoryItem(
                        manifestUrl = url,
                        name = url.substringBefore("?").substringAfterLast('/'),
                        isRefreshing = true,
                    )
                } else {
                    val shouldRefresh = shouldRefreshRepository(
                        repository = existing,
                        scrapers = existingState.scrapers,
                        nowEpochMs = nowEpochMs,
                    )
                    existing.copy(
                        isRefreshing = shouldRefresh,
                        errorMessage = if (shouldRefresh) null else existing.errorMessage,
                    )
                }
            }
            val nextScrapers = existingState.scrapers.filter { scraper ->
                urls.contains(scraper.repositoryUrl)
            }

            val applied = runIfCurrent(operation) {
                _uiState.value = PluginsUiState(
                    pluginsEnabled = existingState.pluginsEnabled,
                    groupStreamsByRepository = existingState.groupStreamsByRepository,
                    repositories = nextRepos,
                    scrapers = nextScrapers,
                )
                initialized = true
            }
            if (!applied) return@runCatchingUnlessCancelled
            persist(operation)

            nextRepos.filter(PluginRepositoryItem::isRefreshing).forEach { repository ->
                if (!isCurrentOperation(operation)) return@forEach
                refreshRepositoryInternal(
                    repository.manifestUrl,
                    pushAfterRefresh = false,
                    ensureInitialized = false,
                    operation = operation,
                    client = client,
                )
            }
        }.onFailure { error ->
            log.e(error) { "pullFromServer failed" }
        }
    }

    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult {
        initialize()
        val operation = currentProfileOperation()
            ?: throw CancellationException("Plugin profile changed before repository installation started")
        val client = SupabaseProvider.client
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_error_enter_valid_url))
        }

        val duplicate = readIfCurrent(operation) {
            _uiState.value.repositories.any { it.manifestUrl == manifestUrl }
        } ?: throw CancellationException("Plugin profile changed before repository installation started")
        if (duplicate) {
            return AddPluginRepositoryResult.Error(getString(Res.string.plugins_repository_already_installed))
        }

        return try {
            val previousById = readIfCurrent(operation) {
                _uiState.value.scrapers.associateBy { it.id }
            } ?: throw CancellationException("Plugin profile changed before repository fetch started")
            val (repository, scrapers) = fetchRepositoryData(
                manifestUrl = manifestUrl,
                previousScrapers = previousById,
                operation = operation,
            )
            val applied = runIfCurrent(operation) {
                _uiState.update { state ->
                    state.copy(
                        repositories = state.repositories + repository,
                        scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers,
                    )
                }
            }
            if (!applied) throw CancellationException("Plugin profile changed while installing repository")
            persist(operation)
            pushToServer(operation, client)
            AddPluginRepositoryResult.Success(repository)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_repository_install_failed))
        }
    }

    actual fun removeRepository(manifestUrl: String) {
        initialize()
        val operation = currentProfileOperation() ?: return
        val client = SupabaseProvider.client
        val applied = runIfCurrent(operation) {
            _uiState.update { state ->
                state.copy(
                    repositories = state.repositories.filterNot { it.manifestUrl == manifestUrl },
                    scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl },
                )
            }
        }
        if (applied) {
            persist(operation)
            pushToServer(operation, client)
        }
    }

    actual fun refreshAll() {
        initialize()
        val operation = currentProfileOperation() ?: return
        val repositories = readIfCurrent(operation) { _uiState.value.repositories } ?: return
        val client = SupabaseProvider.client
        repositories.forEach { repository ->
            if (!isCurrentOperation(operation)) return@forEach
            refreshRepositoryInternal(
                repository.manifestUrl,
                pushAfterRefresh = false,
                ensureInitialized = false,
                operation = operation,
                client = client,
            )
        }
    }

    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) {
        refreshRepositoryInternal(manifestUrl, pushAfterRefresh, ensureInitialized = true)
    }

    private fun refreshRepositoryInternal(
        manifestUrl: String,
        pushAfterRefresh: Boolean,
        ensureInitialized: Boolean,
        operation: PluginProfileOperation? = null,
        client: SupabaseClient? = null,
    ) {
        if (ensureInitialized) initialize()
        val activeOperation = operation ?: currentProfileOperation() ?: return
        if (!isCurrentOperation(activeOperation)) return
        val activeClient = client ?: SupabaseProvider.client
        var refreshJob: Job? = null
        val scheduled = runIfCurrent(activeOperation) {
            synchronized(activeRefreshJobsLock) {
                if (activeRefreshJobs[manifestUrl]?.isActive == true) return@synchronized
                markRefreshing(manifestUrl)
                refreshJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val previous = readIfCurrent(activeOperation) {
                            _uiState.value.scrapers.associateBy { it.id }
                        } ?: return@launch
                        val result = runCatchingUnlessCancelled {
                            fetchRepositoryData(manifestUrl, previous, activeOperation)
                        }
                        val applied = runIfCurrent(activeOperation) {
                            result.fold(
                                onSuccess = { (repository, scrapers) ->
                                    _uiState.update { state ->
                                        if (state.repositories.none { it.manifestUrl == manifestUrl }) {
                                            state
                                        } else {
                                            state.copy(
                                                repositories = state.repositories.map { existing ->
                                                    if (existing.manifestUrl == manifestUrl) repository else existing
                                                },
                                                scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers,
                                            )
                                        }
                                    }
                                },
                                onFailure = { error ->
                                    _uiState.update { state ->
                                        state.copy(
                                            repositories = state.repositories.map { existing ->
                                                if (existing.manifestUrl == manifestUrl) {
                                                    existing.copy(
                                                        isRefreshing = false,
                                                        errorMessage = error.message
                                                            ?: runBlocking { getString(Res.string.plugins_repository_refresh_failed) },
                                                    )
                                                } else {
                                                    existing
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        if (applied) persist(activeOperation)
                        if (applied && pushAfterRefresh && isCurrentOperation(activeOperation)) {
                            pushToServer(activeOperation, activeClient)
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
        if (scheduled) refreshJob?.start()
    }

    actual fun toggleScraper(scraperId: String, enabled: Boolean) {
        initialize()
        val operation = currentProfileOperation() ?: return
        runIfCurrent(operation) {
            _uiState.update { state ->
                state.copy(
                    scrapers = state.scrapers.map { scraper ->
                        if (scraper.id == scraperId) {
                            scraper.copy(enabled = if (scraper.manifestEnabled) enabled else false)
                        } else {
                            scraper
                        }
                    },
                )
            }
        }
        if (isCurrentOperation(operation)) persist(operation)
    }

    actual fun setPluginsEnabled(enabled: Boolean) {
        initialize()
        val operation = currentProfileOperation() ?: return
        runIfCurrent(operation) {
            _uiState.update { it.copy(pluginsEnabled = enabled) }
        }
        if (isCurrentOperation(operation)) persist(operation)
    }

    actual fun setGroupStreamsByRepository(enabled: Boolean) {
        initialize()
        val operation = currentProfileOperation() ?: return
        runIfCurrent(operation) {
            _uiState.update { it.copy(groupStreamsByRepository = enabled) }
        }
        if (isCurrentOperation(operation)) persist(operation)
    }

    actual fun setLocalPluginSearchPaused(paused: Boolean) {
        PluginRuntime.setSearchPaused(paused)
    }

    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> {
        initialize()
        if (!_uiState.value.pluginsEnabled) return emptyList()
        return _uiState.value.scrapers.filter { scraper ->
            scraper.enabled && scraper.supportsType(type)
        }
    }

    actual suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>> {
        initialize()
        val scraper = _uiState.value.scrapers.find { it.id == scraperId }
            ?: return Result.failure(IllegalArgumentException(getString(Res.string.plugins_error_provider_not_found)))

        val mediaType = if (scraper.supportsType("movie")) "movie" else "tv"
        val season = if (mediaType == "tv") 1 else null
        val episode = if (mediaType == "tv") 1 else null
        return executeScraperInternal(
            scraper = scraper,
            tmdbId = "603",
            mediaType = mediaType,
            season = season,
            episode = episode,
            respectSearchPause = false,
        )
    }

    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>> = executeScraperInternal(
        scraper = scraper,
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        respectSearchPause = true,
    )

    private suspend fun executeScraperInternal(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        respectSearchPause: Boolean,
    ): Result<List<PluginRuntimeResult>> {
        val resolvedTmdbId = resolvePluginTmdbId(
            tmdbId = tmdbId,
            mediaType = mediaType,
        )

        return runCatchingUnlessCancelled {
            PluginRuntime.executePlugin(
                code = scraper.code,
                tmdbId = resolvedTmdbId,
                mediaType = normalizePluginType(mediaType),
                season = season,
                episode = episode,
                scraperId = scraper.id,
                respectSearchPause = respectSearchPause,
            )
        }
    }

    private suspend fun resolvePluginTmdbId(
        tmdbId: String,
        mediaType: String,
    ): String {
        val trimmed = tmdbId.trim()
        if (trimmed.isBlank()) return tmdbId

        return TmdbService.ensureTmdbId(
            videoId = trimmed,
            mediaType = mediaType,
        ) ?: trimmed
    }

    private suspend fun fetchRepositoryData(
        manifestUrl: String,
        previousScrapers: Map<String, PluginScraper>,
        operation: PluginProfileOperation,
    ): Pair<PluginRepositoryItem, List<PluginScraper>> = withContext(Dispatchers.Default) {
        ensureOperationCurrent(operation)
        val payload = httpGetText(manifestUrl)
        ensureOperationCurrent(operation)
        val manifest = PluginManifestParser.parse(payload)
        val baseUrl = manifestUrl.substringBefore("?").removeSuffix("/manifest.json")
        val scrapers = mutableListOf<PluginScraper>()

        for (info in manifest.scrapers) {
            if (!info.isSupportedOnCurrentPlatform()) continue
            ensureOperationCurrent(operation)
            val codeUrl = if (info.filename.startsWith("http://") || info.filename.startsWith("https://")) {
                info.filename
            } else {
                "$baseUrl/${info.filename.trimStart('/')}"
            }
            val codeResult = runCatchingUnlessCancelled { httpGetText(codeUrl) }
            val code = codeResult.getOrNull() ?: continue
            val scraperId = "${manifestUrl.lowercase()}:${info.id}"
            var cached = false
            val cacheAccepted = runIfCurrent(operation) {
                cached = PluginStorage.saveScraperCode(
                    profileId = operation.profileId,
                    scraperId = scraperId,
                    code = code,
                    overwrite = true,
                )
            }
            if (!cacheAccepted) {
                throw CancellationException("Plugin profile changed while caching scraper code")
            }
            if (!cached) {
                log.w { "Failed to cache plugin scraper $scraperId" }
            }
            val previous = previousScrapers[scraperId]
            val enabled = when {
                !info.enabled -> false
                previous != null -> previous.enabled
                else -> info.enabled
            }
            scrapers += PluginScraper(
                id = scraperId,
                repositoryUrl = manifestUrl,
                name = info.name,
                description = info.description.orEmpty(),
                version = info.version,
                filename = info.filename,
                supportedTypes = info.supportedTypes,
                enabled = enabled,
                manifestEnabled = info.enabled,
                hasSettings = info.hasSettings,
                logo = info.logo,
                contentLanguage = info.contentLanguage ?: emptyList(),
                formats = info.formats ?: info.supportedFormats,
                code = code,
            )
        }

        ensureOperationCurrent(operation)
        val repository = PluginRepositoryItem(
            manifestUrl = manifestUrl,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            scraperCount = scrapers.size,
            lastUpdated = currentEpochMillis(),
            isRefreshing = false,
            errorMessage = null,
        )
        repository to scrapers
    }
    private fun PluginManifestScraper.isSupportedOnCurrentPlatform(): Boolean {
        val platform = currentPluginPlatform().lowercase()
        val supported = supportedPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        val disabled = disabledPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        if (supported.isNotEmpty() && platform !in supported) return false
        if (platform in disabled) return false
        return true
    }

    private fun shouldRefreshRepository(
        repository: PluginRepositoryItem,
        scrapers: List<PluginScraper>,
        nowEpochMs: Long,
    ): Boolean = isPluginRepositoryRefreshDue(repository.lastUpdated, nowEpochMs) ||
        scrapers.count { scraper -> scraper.repositoryUrl == repository.manifestUrl } < repository.scraperCount

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { state ->
            state.copy(
                repositories = state.repositories.map { repo ->
                    if (repo.manifestUrl == manifestUrl) {
                        repo.copy(isRefreshing = true, errorMessage = null)
                    } else {
                        repo
                    }
                },
            )
        }
    }

    private fun pushToServer(
        operation: PluginProfileOperation,
        client: SupabaseClient = SupabaseProvider.client,
    ) {
        val repositories = readIfCurrent(operation) {
            _uiState.value.repositories.mapIndexed { index, repository ->
                PluginPushItem(
                    url = repository.manifestUrl,
                    name = repository.name,
                    enabled = true,
                    sortOrder = index,
                )
            }
        } ?: return

        var pushJob: Job? = null
        pushJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val params = buildJsonObject {
                    put("p_profile_id", operation.profileId)
                    put("p_plugins", json.encodeToJsonElement(repositories))
                }
                client.postgrest.rpc("sync_push_plugins", params)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer failed" }
            } finally {
                synchronized(pushJobsLock) {
                    if (pushJobsByProfile[operation.profileId] === pushJob) {
                        pushJobsByProfile.remove(operation.profileId)
                    }
                }
            }
        }

        var previous: Job? = null
        val queued = runIfCurrent(operation) {
            previous = synchronized(pushJobsLock) {
                pushJobsByProfile.put(operation.profileId, pushJob!!)
            }
        }
        if (!queued) {
            pushJob?.cancel()
            return
        }
        previous?.cancel()
        pushJob!!.start()
    }

    private fun cancelPushJobs() {
        val jobs = synchronized(pushJobsLock) {
            pushJobsByProfile.values.toList().also { pushJobsByProfile.clear() }
        }
        jobs.forEach(Job::cancel)
    }
    private fun persist(operation: PluginProfileOperation) {
        val snapshot = readIfCurrent(operation) {
            PluginPersistenceSnapshot(
                profileId = operation.profileId,
                generation = persistenceGeneration.value,
                revision = persistenceRevision.incrementAndGet(),
                state = _uiState.value,
            )
        } ?: return
        val requiresCodeWrite = snapshot.state.scrapers.any { scraper ->
            !PluginStorage.hasScraperCode(snapshot.profileId, scraper.id)
        }
        if (requiresCodeWrite) {
            scope.launch {
                runIfCurrent(operation) {
                    persist(snapshot)
                }
            }
        } else {
            runIfCurrent(operation) {
                persist(snapshot)
            }
        }
    }
    private fun persist(snapshot: PluginPersistenceSnapshot) {
        if (snapshot.generation != persistenceGeneration.value) return
        var cached = true
        snapshot.state.scrapers.forEach { scraper ->
            val scraperCached = PluginStorage.saveScraperCode(
                profileId = snapshot.profileId,
                scraperId = scraper.id,
                code = scraper.code,
                overwrite = false,
            )
            cached = scraperCached && cached
        }
        if (!cached || snapshot.generation != persistenceGeneration.value) {
            if (!cached) {
                log.w { "Failed to persist plugin scraper cache for profile ${snapshot.profileId}" }
            }
            return
        }

        synchronized(persistenceLock) {
            if (snapshot.generation != persistenceGeneration.value) return@synchronized
            val persistedRevision = persistedRevisionByProfile[snapshot.profileId] ?: Long.MIN_VALUE
            if (snapshot.revision < persistedRevision) return@synchronized
            val payload = snapshot.state.toStoredPluginsState()
            PluginStorage.saveState(snapshot.profileId, json.encodeToString(payload))
            persistedRevisionByProfile[snapshot.profileId] = snapshot.revision
        }
    }

    private fun loadStoredState(profileId: Int): StoredPluginsState? {
        val raw = PluginStorage.loadState(profileId)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            json.decodeFromString<StoredPluginsState>(raw)
        }.getOrNull()
    }

    private fun cancelActiveRefreshes() {
        val jobs = synchronized(activeRefreshJobsLock) {
            activeRefreshJobs.values.toList().also { activeRefreshJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    private fun ensureStateLoadedForProfile(profileId: Int) {
        if (initialized && currentProfileId == profileId) return

        if (currentProfileId != profileId) {
            cancelActiveRefreshes()
        }

        currentProfileId = profileId
        val loadedState = loadStateAsUiState(profileId)
        _uiState.value = loadedState.state
        initialized = true
        if (loadedState.requiresMigration) persist()
    }

    private fun loadStateAsUiState(profileId: Int): LoadedPluginState {
        val stored = loadStoredState(profileId)
        var requiresMigration = false
        val scrapers = stored?.scrapers
            ?.mapNotNull { storedScraper ->
                storedScraper.restorePluginScraper { scraperId ->
                    PluginStorage.loadScraperCode(profileId, scraperId)
                }?.also { restored ->
                    requiresMigration = requiresMigration || restored.requiresMigration
                }?.scraper
            }
            ?: emptyList()
        return LoadedPluginState(
            state = PluginsUiState(
                pluginsEnabled = stored?.pluginsEnabled ?: true,
                groupStreamsByRepository = stored?.groupStreamsByRepository ?: false,
                repositories = stored?.repositories
                    ?.map {
                        PluginRepositoryItem(
                            manifestUrl = it.manifestUrl,
                            name = it.name,
                            description = it.description,
                            version = it.version,
                            scraperCount = it.scraperCount,
                            lastUpdated = it.lastUpdated,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                    ?: emptyList(),
                scrapers = scrapers,
            ),
            requiresMigration = requiresMigration,
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
        require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.plugins_error_enter_repo_url) } }

        val normalizedScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }

        val withoutFragment = normalizedScheme.substringBefore("#")
        val query = withoutFragment.substringAfter("?", "")
        val path = withoutFragment.substringBefore("?").trimEnd('/')
        val manifestPath = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
        return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && !active.id.isBlank() && active.usesPrimaryPlugins) 1 else profileId
    }
}
