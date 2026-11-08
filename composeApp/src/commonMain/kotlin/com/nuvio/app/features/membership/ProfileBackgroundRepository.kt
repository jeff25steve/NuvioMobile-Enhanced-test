package com.nuvio.app.features.membership

import co.touchlab.kermit.Logger
import com.nuvio.app.core.concurrency.mapInBatchesConcurrently
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ProfileBackgroundBucket = "membership-profile-backgrounds"
private const val MaxConcurrentProfileBackgroundLoads = 4

data class ProfileBackgroundCatalogItem(
    val id: String,
    val displayName: String,
    val landscapeImageBytes: ByteArray? = null,
    val portraitImageBytes: ByteArray? = null,
    val assetVersion: Int,
)

object ProfileBackgroundRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileBackgroundRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _catalog = MutableStateFlow<List<ProfileBackgroundCatalogItem>>(emptyList())
    val catalog: StateFlow<List<ProfileBackgroundCatalogItem>> = _catalog.asStateFlow()

    // All repository-owned mutable state and catalog publication share one short,
    // non-suspending critical section. Never hold this lock across network or job waits.
    private val stateLock = SynchronizedObject()
    private var loadJob: Job? = null
    private var assetLoadJob: Job? = null
    private var remoteCatalog = emptyList<SupabaseProfileBackgroundCatalogItem>()
    private var cacheHydrated = false
    private var remoteLoaded = false
    private var generation = 0L
    private var assetRequestId = 0L

    fun ensureLoaded() {
        hydrateFromCacheIfNeeded()
        val jobToStart = synchronized(stateLock) {
            if (remoteLoaded || loadJob?.isCompleted == false) {
                null
            } else {
                val requestGeneration = generation
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val items = SupabaseProvider.client.postgrest
                            .rpc("get_member_profile_background_catalog")
                            .decodeList<SupabaseProfileBackgroundCatalogItem>()
                        synchronized(stateLock) {
                            if (generation == requestGeneration) {
                                remoteCatalog = items
                                publishMetadata(items)
                                saveCachedCatalog(items)
                                remoteLoaded = true
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.w(error) { "Unable to load supporter profile backgrounds" }
                    }
                }.also { loadJob = it }
            }
        }
        jobToStart?.start()
    }

    fun loadSelectedAndPreload(id: String, portrait: Boolean) {
        ensureLoaded()
        val request = synchronized(stateLock) {
            assetRequestId += 1
            ProfileBackgroundLoadRequest(
                generation = generation,
                requestId = assetRequestId,
                initialSelected = remoteCatalog.firstOrNull { it.id == id },
                catalogLoadJob = loadJob,
            )
        }

        // Keep the existing immediate cached-image publication, but guard it against
        // invalidation or a newer asset request racing this synchronous cache read.
        val selectedImageCached = request.initialSelected?.let { item ->
            loadCachedAndPublish(item, portrait, request.generation, request.requestId)
        } == true

        val jobs = synchronized(stateLock) {
            if (generation != request.generation || assetRequestId != request.requestId) {
                null
            } else {
                val previousJob = assetLoadJob
                val nextJob = scope.launch(start = CoroutineStart.LAZY) {
                    request.initialSelected?.let { item ->
                        if (!selectedImageCached) {
                            loadAndPublish(item, portrait, request.generation, request.requestId)
                        }
                    }
                    request.catalogLoadJob?.join()
                    val latestCatalog = synchronized(stateLock) {
                        if (
                            generation == request.generation &&
                            assetRequestId == request.requestId
                        ) {
                            remoteCatalog
                        } else {
                            null
                        }
                    } ?: return@launch

                    val selected = latestCatalog.firstOrNull { it.id == id } ?: return@launch
                    if (selected.assetVersion != request.initialSelected?.assetVersion) {
                        loadAndPublish(selected, portrait, request.generation, request.requestId)
                    }
                    preload(
                        items = latestCatalog.filterNot { it.id == id },
                        portrait = portrait,
                        requestGeneration = request.generation,
                        requestId = request.requestId,
                    )
                }
                assetLoadJob = nextJob
                previousJob to nextJob
            }
        }
        jobs?.first?.cancel()
        jobs?.second?.start()
    }

    fun preloadLandscapeImages() {
        ensureLoaded()
        val jobs = synchronized(stateLock) {
            assetRequestId += 1
            val requestGeneration = generation
            val requestId = assetRequestId
            val initialCatalog = remoteCatalog
            val initialVersions = initialCatalog.associate { it.id to it.assetVersion }
            val catalogLoadJob = loadJob
            val previousJob = assetLoadJob
            val nextJob = scope.launch(start = CoroutineStart.LAZY) {
                preload(
                    items = initialCatalog,
                    portrait = false,
                    requestGeneration = requestGeneration,
                    requestId = requestId,
                )
                catalogLoadJob?.join()
                val latestCatalog = synchronized(stateLock) {
                    if (generation == requestGeneration && assetRequestId == requestId) {
                        remoteCatalog
                    } else {
                        null
                    }
                } ?: return@launch
                preload(
                    items = latestCatalog.filter { initialVersions[it.id] != it.assetVersion },
                    portrait = false,
                    requestGeneration = requestGeneration,
                    requestId = requestId,
                )
            }
            assetLoadJob = nextJob
            previousJob to nextJob
        }
        jobs.first?.cancel()
        jobs.second.start()
    }

    fun invalidate() {
        val jobs = synchronized(stateLock) {
            generation += 1
            assetRequestId += 1
            cacheHydrated = false
            remoteLoaded = false
            val previousJobs = loadJob to assetLoadJob
            loadJob = null
            assetLoadJob = null
            remoteCatalog = emptyList()
            _catalog.value = emptyList()
            previousJobs
        }
        jobs.first?.cancel()
        jobs.second?.cancel()
    }

    private fun hydrateFromCacheIfNeeded() {
        synchronized(stateLock) {
            if (!cacheHydrated) {
                cacheHydrated = true
                val payload = MemberAssetStorage.loadProfileBackgroundCatalogPayload().orEmpty().trim()
                if (payload.isNotEmpty()) {
                    val stored = runCatching {
                        json.decodeFromString<StoredProfileBackgroundCatalogPayload>(payload)
                    }.getOrNull()
                    if (stored != null) {
                        remoteCatalog = stored.items
                        publishMetadata(stored.items)
                    }
                }
            }
        }
    }

    private fun publishMetadata(items: List<SupabaseProfileBackgroundCatalogItem>) {
        val current = _catalog.value.associateBy(ProfileBackgroundCatalogItem::id)
        _catalog.value = items.map { item ->
            val cached = current[item.id]?.takeIf { it.assetVersion == item.assetVersion }
            ProfileBackgroundCatalogItem(
                id = item.id,
                displayName = item.displayName,
                landscapeImageBytes = cached?.landscapeImageBytes,
                portraitImageBytes = cached?.portraitImageBytes,
                assetVersion = item.assetVersion,
            )
        }
    }

    private fun saveCachedCatalog(items: List<SupabaseProfileBackgroundCatalogItem>) {
        MemberAssetStorage.saveProfileBackgroundCatalogPayload(
            json.encodeToString(StoredProfileBackgroundCatalogPayload(items)),
        )
    }

    private fun loadCachedAndPublish(
        item: SupabaseProfileBackgroundCatalogItem,
        portrait: Boolean,
        requestGeneration: Long,
        requestId: Long,
    ): Boolean {
        return try {
            val landscape = MemberAssetStorage.loadProfileBackground(
                "${item.id}-v${item.assetVersion}",
            )
            val portraitBytes = if (portrait) {
                item.portraitStoragePath?.let {
                    MemberAssetStorage.loadProfileBackground(
                        "${item.id}-portrait-v${item.assetVersion}",
                    )
                }
            } else {
                null
            }
            val bytes = if (portrait) {
                portraitBytes ?: landscape ?: return false
            } else {
                landscape ?: return false
            }
            publishImage(item, portrait, bytes, requestGeneration, requestId)

            // A landscape fallback is displayed immediately, but a configured portrait
            // asset must still be downloaded when its own versioned cache entry is absent.
            !portrait || item.portraitStoragePath == null || portraitBytes != null
        } catch (error: Exception) {
            log.w(error) { "Unable to read cached profile background ${item.id}" }
            false
        }
    }

    private suspend fun preload(
        items: List<SupabaseProfileBackgroundCatalogItem>,
        portrait: Boolean,
        requestGeneration: Long,
        requestId: Long,
    ) {
        mapInBatchesConcurrently(items, MaxConcurrentProfileBackgroundLoads) { item ->
            loadAndPublish(item, portrait, requestGeneration, requestId)
        }
    }

    private suspend fun loadAndPublish(
        item: SupabaseProfileBackgroundCatalogItem,
        portrait: Boolean,
        requestGeneration: Long,
        requestId: Long,
    ) {
        try {
            val bytes = if (portrait) loadPortraitImage(item) else loadLandscapeImage(item)
            publishImage(item, portrait, bytes, requestGeneration, requestId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter profile background ${item.id}" }
        }
    }

    private fun publishImage(
        item: SupabaseProfileBackgroundCatalogItem,
        portrait: Boolean,
        bytes: ByteArray,
        requestGeneration: Long,
        requestId: Long,
    ) {
        synchronized(stateLock) {
            if (generation == requestGeneration && assetRequestId == requestId) {
                _catalog.update { catalog ->
                    catalog.map { background ->
                        if (background.id != item.id || background.assetVersion != item.assetVersion) {
                            background
                        } else if (portrait) {
                            background.copy(portraitImageBytes = bytes)
                        } else {
                            background.copy(landscapeImageBytes = bytes)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadPortraitImage(item: SupabaseProfileBackgroundCatalogItem): ByteArray {
        val portraitPath = item.portraitStoragePath ?: return loadLandscapeImage(item)
        return try {
            loadImage(
                cacheKey = "${item.id}-portrait-v${item.assetVersion}",
                storagePath = portraitPath,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load portrait background ${item.id}" }
            loadLandscapeImage(item)
        }
    }

    private suspend fun loadLandscapeImage(item: SupabaseProfileBackgroundCatalogItem): ByteArray =
        loadImage(
            cacheKey = "${item.id}-v${item.assetVersion}",
            storagePath = item.storagePath,
        )

    private suspend fun loadImage(cacheKey: String, storagePath: String): ByteArray =
        MemberAssetStorage.loadProfileBackground(cacheKey)
            ?: SupabaseProvider.client.storage[ProfileBackgroundBucket]
                .downloadAuthenticated(storagePath)
                .also { MemberAssetStorage.saveProfileBackground(cacheKey, it) }
}

private data class ProfileBackgroundLoadRequest(
    val generation: Long,
    val requestId: Long,
    val initialSelected: SupabaseProfileBackgroundCatalogItem?,
    val catalogLoadJob: Job?,
)

@Serializable
private data class StoredProfileBackgroundCatalogPayload(
    val items: List<SupabaseProfileBackgroundCatalogItem> = emptyList(),
)

@Serializable
private data class SupabaseProfileBackgroundCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("portrait_storage_path") val portraitStoragePath: String? = null,
    @SerialName("asset_version") val assetVersion: Int,
)
