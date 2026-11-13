package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridServiceCredential
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.supports
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloud_library_playback_disabled
import nuvio.composeapp.generated.resources.cloud_library_provider_unavailable
import org.jetbrains.compose.resources.getString

internal class CloudLibraryStore(
    private val credentialsProvider: suspend () -> List<DebridServiceCredential>,
    private val providerApis: List<CloudLibraryProviderApi>,
) {
    suspend fun refresh(): CloudLibraryUiState {
        val credentials = credentialsProvider()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = coroutineScope {
            credentials.map { credential ->
                async {
                    val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
                    if (api == null) {
                        return@async CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = getString(
                                Res.string.cloud_library_provider_unavailable,
                                credential.provider.displayName,
                            ),
                        )
                    }

                    api.listItems(credential.apiKey)
                        .fold(
                            onSuccess = { items ->
                                CloudLibraryProviderState(
                                    provider = credential.provider,
                                    items = items,
                                )
                            },
                            onFailure = { error ->
                                CloudLibraryProviderState(
                                    provider = credential.provider,
                                    errorMessage = error.message,
                                )
                            },
                        )
                }
            }.awaitAll()
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isRefreshing = false,
            providers = providerStates,
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val credential = credentialsProvider()
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        file.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes,
            )
        }
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file,
        )
    }
}

object CloudLibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CloudLibraryStore(
        credentialsProvider = {
            DebridSettingsRepository.ensureLoaded()
            DebridProviders.configuredServices(DebridSettingsRepository.snapshot())
        },
        providerApis = CloudLibraryProviderApis.all(),
    )
    private val _uiState = MutableStateFlow(CloudLibraryUiState())
    private val refreshLock = SynchronizedObject()
    private val cacheSaveMutex = Mutex()
    private var refreshGeneration = 0L
    private var loadedConnectionKeys: List<CloudConnectionKey> = emptyList()
    private var restoredConnectionKeys: List<CloudConnectionKey> = emptyList()
    val uiState = _uiState.asStateFlow()

    fun ensureLoaded() {
        DebridSettingsRepository.ensureLoaded()
package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridServiceCredential
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.supports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloud_library_playback_disabled
import nuvio.composeapp.generated.resources.cloud_library_provider_unavailable
import org.jetbrains.compose.resources.getString

internal class CloudLibraryStore(
    private val credentialsProvider: suspend () -> List<DebridServiceCredential>,
    private val providerApis: List<CloudLibraryProviderApi>,
) {
    suspend fun refresh(): CloudLibraryUiState {
        val credentials = credentialsProvider()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = getString(
                        Res.string.cloud_library_provider_unavailable,
                        credential.provider.displayName,
                    ),
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items,
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message,
                        )
                    },
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isRefreshing = false,
            providers = providerStates,
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val credential = credentialsProvider()
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        file.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes,
            )
        }
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file,
        )
    }
}

object CloudLibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CloudLibraryStore(
        credentialsProvider = {
            DebridSettingsRepository.ensureLoaded()
            DebridProviders.configuredServices(DebridSettingsRepository.snapshot())
        },
        providerApis = CloudLibraryProviderApis.all(),
    )
    private val _uiState = MutableStateFlow(CloudLibraryUiState())
    private val refreshLock = SynchronizedObject()
    private val cacheSaveMutex = Mutex()
    private var refreshGeneration = 0L
    private var loadedConnectionKeys: List<CloudConnectionKey> = emptyList()
    private var restoredConnectionKeys: List<CloudConnectionKey> = emptyList()
    val uiState = _uiState.asStateFlow()

    fun ensureLoaded() {
        DebridSettingsRepository.ensureLoaded()

        val current = _uiState.value
        if (current.isRefreshing) return
        val connectedKeys = connectedCloudConnectionKeys()
        if (!current.isLoaded || connectedKeys != loadedConnectionKeys) {
            refreshInternal(cachedKeys = connectedKeys)
        }
    }

    fun refresh() {
        refreshInternal(cachedKeys = null)
    }

    private fun refreshInternal(cachedKeys: List<CloudConnectionKey>?) {
        DebridSettingsRepository.ensureLoaded()
        val requestGeneration = beginRefreshGeneration()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            invalidateRefreshesAndDisable()
            return
        }
        val requestedConnectionKeys = connectedCloudConnectionKeys()
        _uiState.update { current ->
            current.copy(
                isEnabled = true,
                isRefreshing = true,
                providers = current.providers.map { it.copy(isLoading = true, errorMessage = null) },
            )
        }
        scope.launch {
            if (cachedKeys != null) {
                restoreCachedState(cachedKeys, requestGeneration)
            }
            if (!isRefreshGenerationCurrent(requestGeneration)) return@launch
            val previous = _uiState.value
            val refreshed = store.refresh().keepingItemsOnFailure(previous)
            publishRefreshResult(
                requestGeneration = requestGeneration,
                requestedConnectionKeys = requestedConnectionKeys,
                refreshed = refreshed,
            )
        }
    }

    suspend fun findPlaybackTargetForProgress(
        contentId: String,
        videoId: String,
    ): CloudLibraryPlaybackTarget? =
        when (val result = findPlaybackTargetForProgressResult(contentId = contentId, videoId = videoId)) {
            is CloudLibraryPlaybackTargetLookupResult.Found -> result.target
            CloudLibraryPlaybackTargetLookupResult.Disabled,
            is CloudLibraryPlaybackTargetLookupResult.NotConnected,
            CloudLibraryPlaybackTargetLookupResult.NotFound,
            -> null
        }

    suspend fun findPlaybackTargetForProgressResult(
        contentId: String,
        videoId: String,
    ): CloudLibraryPlaybackTargetLookupResult {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            invalidateRefreshesAndDisable()
            return CloudLibraryPlaybackTargetLookupResult.Disabled
        }

        val providerId = cloudLibraryProviderId(contentId)
            .ifBlank { cloudLibraryProviderId(videoId) }
        val connectedCredentials = connectedCloudCredentials()
        if (connectedCredentials.isEmpty()) {
            return CloudLibraryPlaybackTargetLookupResult.NotConnected(
                providerName = providerId.takeIf { it.isNotBlank() }?.let(DebridProviders::displayName),
            )
        }
        if (
            providerId.isNotBlank() &&
            connectedCredentials.none { credential -> credential.provider.id.equals(providerId, ignoreCase = true) }
        ) {
            return CloudLibraryPlaybackTargetLookupResult.NotConnected(
                providerName = DebridProviders.displayName(providerId),
            )
        }

        val connectedKeys = connectedCloudConnectionKeys()
        if (connectedKeys != loadedConnectionKeys && connectedKeys != restoredConnectionKeys) {
            restoreCachedState(connectedKeys)
        }

        _uiState.value.findPlaybackTargetForProgress(
            contentId = contentId,
            videoId = videoId,
        )?.let { target -> return CloudLibraryPlaybackTargetLookupResult.Found(target) }

        val refreshed = refreshNow()
        val refreshedTarget = refreshed.findPlaybackTargetForProgress(
            contentId = contentId,
            videoId = videoId,
        )
        return if (refreshedTarget != null) {
            CloudLibraryPlaybackTargetLookupResult.Found(refreshedTarget)
        } else {
            CloudLibraryPlaybackTargetLookupResult.NotFound
        }
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        DebridSettingsRepository.ensureLoaded()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed(getString(Res.string.cloud_library_playback_disabled))
        }
        val result = store.resolvePlayback(item, file)
        if (result is CloudLibraryPlaybackResult.Success) {
            rememberResolvedPlaybackUrl(item = item, file = file, url = result.url)
        }
        return result
    }

    private fun rememberResolvedPlaybackUrl(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        url: String,
    ) {
        if (url.isBlank()) return
        _uiState.update { current ->
            current.withResolvedPlaybackUrl(
                item = item,
                file = file,
                url = url,
            )
        }
    }

    private fun connectedCloudCredentials(): List<DebridServiceCredential> =
        DebridSettingsRepository.snapshot()
            .takeIf { settings -> settings.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

    private fun connectedCloudConnectionKeys(): List<CloudConnectionKey> =
        connectedCloudCredentials().map { credential ->
            CloudConnectionKey(
                providerId = credential.provider.id,
                apiKeyHash = credential.apiKey.hashCode(),
            )
        }.sortedBy { it.providerId }

    private suspend fun refreshNow(): CloudLibraryUiState {
        DebridSettingsRepository.ensureLoaded()
        val requestGeneration = beginRefreshGeneration()
        if (!DebridSettingsRepository.snapshot().cloudLibraryEnabled) {
            invalidateRefreshesAndDisable()
            return _uiState.value
        }
        val requestedConnectionKeys = connectedCloudConnectionKeys()
        _uiState.update { current ->
            current.copy(
                isEnabled = true,
                isRefreshing = true,
                providers = current.providers.map { it.copy(isLoading = true, errorMessage = null) },
            )
        }
        val previous = _uiState.value
        val refreshed = store.refresh().keepingItemsOnFailure(previous)
        val applied = publishRefreshResult(
            requestGeneration = requestGeneration,
            requestedConnectionKeys = requestedConnectionKeys,
            refreshed = refreshed,
        )
        return if (applied) refreshed else _uiState.value
    }

    private suspend fun restoreCachedState(keys: List<CloudConnectionKey>, requestGeneration: Long) {
        if (keys.isEmpty()) return
        val cachedProviders = withContext(Dispatchers.Default) {
            CloudLibraryCacheStore.load(keys.cacheSignature())
        } ?: return
        if (connectedCloudConnectionKeys() != keys) return
        synchronized(refreshLock) {
            if (requestGeneration != refreshGeneration) return
            restoredConnectionKeys = keys
            _uiState.update { current ->
                current.copy(
                    isLoaded = true,
                    isEnabled = true,
                    providers = cachedProviders.map { providerState ->
                        providerState.copy(isLoading = current.isRefreshing)
                    },
                )
            }
        }
    }

    private fun beginRefreshGeneration(): Long = synchronized(refreshLock) {
        refreshGeneration += 1
        refreshGeneration
    }

    private fun isRefreshGenerationCurrent(requestGeneration: Long): Boolean =
        synchronized(refreshLock) { requestGeneration == refreshGeneration }

    private fun invalidateRefreshesAndDisable() {
        synchronized(refreshLock) {
            refreshGeneration += 1
            loadedConnectionKeys = emptyList()
            _uiState.value = CloudLibraryUiState(isLoaded = true, isEnabled = false)
        }
    }

    private fun publishRefreshResult(
        requestGeneration: Long,
        requestedConnectionKeys: List<CloudConnectionKey>,
        refreshed: CloudLibraryUiState,
    ): Boolean {
        val currentConnectionKeys = connectedCloudConnectionKeys()
        val cloudLibraryEnabled = DebridSettingsRepository.snapshot().cloudLibraryEnabled
        var shouldReloadForChangedConnections = false
        val applied = synchronized(refreshLock) {
            if (
                !shouldApplyCloudLibraryRefresh(
                    requestGeneration = requestGeneration,
                    latestRequestGeneration = refreshGeneration,
                    requestedConnectionSignature = requestedConnectionKeys.cacheSignature(),
                    currentConnectionSignature = currentConnectionKeys.cacheSignature(),
                    cloudLibraryEnabled = cloudLibraryEnabled,
                )
            ) {
                if (requestGeneration == refreshGeneration) {
                    if (!cloudLibraryEnabled) {
                        refreshGeneration += 1
                        loadedConnectionKeys = emptyList()
                        _uiState.value = CloudLibraryUiState(isLoaded = true, isEnabled = false)
                    } else {
                        _uiState.update { current ->
                            current.copy(
                                isRefreshing = false,
                                providers = current.providers.map { it.copy(isLoading = false) },
                            )
                        }
                        shouldReloadForChangedConnections = true
                    }
                }
                false
            } else {
                loadedConnectionKeys = currentConnectionKeys
                _uiState.value = refreshed
                true
            }
        }
        if (shouldReloadForChangedConnections) {
            scope.launch { ensureLoaded() }
        }
        if (applied) {
            cacheSaveMutex.lock()
            try {
                val stillCurrent = synchronized(refreshLock) {
                    requestGeneration == refreshGeneration &&
                        connectedCloudConnectionKeys() == currentConnectionKeys &&
                        DebridSettingsRepository.snapshot().cloudLibraryEnabled
                }
                if (stillCurrent) {
                    CloudLibraryCacheStore.save(currentConnectionKeys.cacheSignature(), refreshed.providers)
                }
            } finally {
                cacheSaveMutex.unlock()
            }
        }
        return applied
    }

    private fun List<CloudConnectionKey>.cacheSignature(): List<String> =
        map { key -> "${key.providerId}:${key.apiKeyHash}" }

    private data class CloudConnectionKey(
        val providerId: String,
        val apiKeyHash: Int,
    )
}

internal fun CloudLibraryUiState.keepingItemsOnFailure(
    previous: CloudLibraryUiState,
): CloudLibraryUiState =
    copy(
        providers = providers.map { providerState ->
            if (providerState.errorMessage.isNullOrBlank() || providerState.items.isNotEmpty()) {
                return@map providerState
            }
            val previousItems = previous.providers
                .firstOrNull { it.providerId == providerState.providerId }
                ?.items
                .orEmpty()
            if (previousItems.isEmpty()) providerState else providerState.copy(items = previousItems)
        },
    )

internal fun CloudLibraryUiState.findPlaybackTargetForProgress(
    contentId: String,
    videoId: String,
): CloudLibraryPlaybackTarget? {
    val normalizedContentId = contentId.trim()
    val normalizedVideoId = videoId.trim()
    if (normalizedContentId.isBlank()) return null

    var singleMatchingItem: CloudLibraryItem? = null
    var hasMultipleMatchingItems = false
    for (providerState in providers) {
        for (item in providerState.items) {
            if (item.stableKey != normalizedContentId) continue

            if (singleMatchingItem == null) {
                singleMatchingItem = item
            } else {
                hasMultipleMatchingItems = true
            }

            for (file in item.files) {
                if (file.playable && item.playbackVideoId(file) == normalizedVideoId) {
                    return CloudLibraryPlaybackTarget(item = item, file = file)
                }
            }
        }
    }

    if (hasMultipleMatchingItems) return null
    val singleItem = singleMatchingItem ?: return null
    var singlePlayableFile: CloudLibraryFile? = null
    for (file in singleItem.files) {
        if (!file.playable) continue
        if (singlePlayableFile != null) return null
        singlePlayableFile = file
    }
    val singleFile = singlePlayableFile ?: return null
    return CloudLibraryPlaybackTarget(item = singleItem, file = singleFile)
}

internal fun CloudLibraryUiState.withResolvedPlaybackUrl(
    item: CloudLibraryItem,
    file: CloudLibraryFile,
    url: String,
): CloudLibraryUiState {
    val normalizedUrl = url.trim().takeIf { it.isNotBlank() } ?: return this
    val targetItemKey = item.stableKey
    val targetFileKey = file.stableKey
    var didUpdate = false
    val updatedProviders = providers.map { providerState ->
        if (providerState.providerId != item.providerId) return@map providerState
        val updatedItems = providerState.items.map { candidateItem ->
            if (candidateItem.stableKey != targetItemKey) return@map candidateItem
            val updatedFiles = candidateItem.files.map { candidateFile ->
                if (candidateFile.stableKey != targetFileKey) {
                    candidateFile
                } else {
                    didUpdate = true
                    candidateFile.copy(playbackUrl = normalizedUrl)
                }
            }
            candidateItem.copy(files = updatedFiles)
        }
        providerState.copy(items = updatedItems)
    }
    return if (didUpdate) {
        copy(providers = updatedProviders)
    } else {
        this
    }
}
