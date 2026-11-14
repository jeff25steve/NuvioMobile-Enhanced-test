package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthSessionIdentity
import com.nuvio.app.core.auth.userId
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<DownloadOperationKey, DownloadsTaskHandle>()
    private val activeOperations = mutableMapOf<DownloadOperationKey, Long>()
    private var nextOperationGeneration = 0L
    private var hasLoaded = false
    private var loadedProfileOwner: DownloadProfileOwner? = null
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        ensureLoadedForCurrentProfile()
    }

    private fun ensureLoadedForCurrentProfile(): DownloadProfileOwner {
        val owner = currentProfileOwner()
        if (!hasLoaded || loadedProfileOwner != owner) loadFromDisk(owner)
        return owner
    }

    fun onProfileChanged() {
        loadFromDisk(currentProfileOwner())
    }

    private fun currentProfileOwner(): DownloadProfileOwner {
        val profileIndex = ProfileRepository.activeProfileId
        val profile = ProfileRepository.state.value.profiles.firstOrNull {
            it.profileIndex == profileIndex
        }
        return DownloadProfileOwner(
            profileIndex = profileIndex,
            userId = AuthRepository.state.value.userId,
            profileId = profile?.id,
        )
    }

    private fun isProfileOwnerCurrent(owner: DownloadProfileOwner): Boolean {
        if (AuthRepository.state.value.userId != owner.userId) return false
        val profileState = ProfileRepository.state.value
        if (!profileState.isLoaded || profileState.profiles.isEmpty()) {
            return ProfileRepository.activeProfileId == owner.profileIndex
        }
        val profile = profileState.profiles.firstOrNull {
            it.profileIndex == owner.profileIndex
        } ?: return false
        if (
            !owner.profileId.isNullOrBlank() &&
            profile.id.isNotBlank() &&
            profile.id != owner.profileId
        ) return false
        if (
            !owner.userId.isNullOrBlank() &&
            profile.userId.isNotBlank() &&
            profile.userId != owner.userId
        ) return false
        return true
    }

    private fun isCurrentOperation(key: DownloadOperationKey, generation: Long): Boolean =
        activeOperations[key] == generation

    private fun cancelDownloadOperation(owner: DownloadProfileOwner, downloadId: String) {
        val key = DownloadOperationKey(owner, downloadId)
        activeOperations.remove(key)
        activeHandles.remove(key)?.cancel()
    }

    private fun finishDownloadOperation(key: DownloadOperationKey, generation: Long) {
        if (!isCurrentOperation(key, generation)) return
        activeOperations.remove(key)
        activeHandles.remove(key)
    }

    private fun updateOwnedDownload(
        owner: DownloadProfileOwner,
        downloadId: String,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean {
        if (!isProfileOwnerCurrent(owner)) return false
        val updateVisibleState =
            loadedProfileOwner == owner && ProfileRepository.activeProfileId == owner.profileIndex
        val sourceItems = if (updateVisibleState) {
            _uiState.value.items
        } else {
            DownloadsCodec.decodeItems(DownloadsStorage.loadPayload(owner.profileIndex).orEmpty())
        }
        var newlyCompleted = false
        val updatedItems = updateProfileDownloadItem(sourceItems, downloadId) { current ->
            transform(current).also { updated ->
                newlyCompleted = newlyCompleted || isCompletedTransition(current.status, updated.status)
            }
        } ?: return false
        DownloadsStorage.savePayload(
            profileIndex = owner.profileIndex,
            payload = DownloadsCodec.encodeItems(updatedItems),
        )
        if (updateVisibleState) publish(updatedItems, newlyCompleted)
        return true
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        val handles = activeHandles.values.toList()
        activeOperations.clear()
        activeHandles.clear()
        handles.forEach { handle -> runCatching { handle.cancel() } }
        hasLoaded = false
        loadedProfileOwner = null
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    /**
     * Deletes downloads belonging to one profile after the profile-delete RPC succeeds.
     * The original auth identity and profile ID fence delayed responses from replacement state.
     */
    internal suspend fun deleteProfileData(
        profileIndex: Int,
        expectedIdentity: AuthSessionIdentity,
        expectedProfileId: String?,
    ): Boolean {
        if (AuthRepository.captureSessionIdentity() != expectedIdentity) return false
        val prepared = runCatching { DownloadsPlatformDownloader.prepareForStartup() }.isSuccess
        if (AuthRepository.captureSessionIdentity() != expectedIdentity) return false

        return withContext(Dispatchers.IO) {
            val profile = ProfileRepository.state.value.profiles.firstOrNull {
                it.profileIndex == profileIndex
            }
            if (!expectedProfileId.isNullOrBlank() && profile?.id != expectedProfileId) {
                return@withContext false
            }
            if (
                profile != null &&
                profile.userId.isNotBlank() &&
                profile.userId != expectedIdentity.userId
            ) return@withContext false

            val persistedItems = DownloadsCodec.decodeItems(
                DownloadsStorage.loadPayload(profileIndex).orEmpty(),
            )
            val visibleItems = _uiState.value.items.takeIf {
                loadedProfileOwner?.profileIndex == profileIndex &&
                    loadedProfileOwner?.userId == expectedIdentity.userId
            }.orEmpty()
            val plan = buildDownloadProfileCleanupPlan(
                profileIndex = profileIndex,
                userId = expectedIdentity.userId,
                activeOperationKeys = activeOperations.keys,
                persistedItems = persistedItems,
                visibleItems = visibleItems,
            )

            val handles = plan.operationKeys.mapNotNull { key ->
                activeOperations.remove(key)
                activeHandles.remove(key)
            }
            handles.forEach { handle -> runCatching { handle.cancel() } }

            var cleanupSucceeded = prepared || plan.items.none { it.status == DownloadStatus.Downloading }
            try {
                DownloadsStorage.savePayload(
                    profileIndex = profileIndex,
                    payload = DownloadsCodec.encodeItems(emptyList()),
                )
            } catch (_: Exception) {
                cleanupSucceeded = false
            }
            plan.items.forEach { item ->
                if (!removeDownloadArtifacts(item)) cleanupSucceeded = false
            }

            if (
                loadedProfileOwner?.profileIndex == profileIndex &&
                loadedProfileOwner?.userId == expectedIdentity.userId
            ) {
                _hasUnseenCompleted.value = false
                publish(emptyList())
            }
            cleanupSucceeded
        }
    }

    /**
     * Download manifests are keyed only by profile index, so account cleanup must remove all of
     * them before another account can reuse the same profile indexes.
     */
    suspend fun clearAccountData(expectedAccountUserId: String?): Boolean {
        val currentUserId = AuthRepository.state.value.userId
        if (currentUserId != null && currentUserId != expectedAccountUserId) return false
        val prepared = runCatching { DownloadsPlatformDownloader.prepareForStartup() }.isSuccess

        return withContext(Dispatchers.IO) {
            val handles = activeHandles.values.toList()
            activeOperations.clear()
            activeHandles.clear()
            handles.forEach { handle -> runCatching { handle.cancel() } }

            val persistedItems = (1..MAX_PROFILES).flatMap { profileIndex ->
                DownloadsCodec.decodeItems(DownloadsStorage.loadPayload(profileIndex).orEmpty())
            }
            val cleanupItems = uniqueDownloadCleanupItems(
                visibleItems = _uiState.value.items,
                persistedItems = persistedItems,
            )
            var cleanupSucceeded = prepared || cleanupItems.none { it.status == DownloadStatus.Downloading }

            (1..MAX_PROFILES).forEach { profileIndex ->
                try {
                    DownloadsStorage.savePayload(
                        profileIndex = profileIndex,
                        payload = DownloadsCodec.encodeItems(emptyList()),
                    )
                } catch (_: Exception) {
                    cleanupSucceeded = false
                }
            }
            cleanupItems.forEach { item ->
                if (!removeDownloadArtifacts(item)) cleanupSucceeded = false
            }

            hasLoaded = false
            loadedProfileOwner = null
            _hasUnseenCompleted.value = false
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            cleanupSucceeded
        }
    }

    private fun removeDownloadArtifacts(item: DownloadItem): Boolean {
        var cleanupSucceeded = true
        // Fence/cancel the transfer before deleting the completed file. On iOS a background
        // delegate callback can otherwise finalize the file again after cleanup begins.
        if (!runCatching {
            DownloadsPlatformDownloader.removePartialFile(item.id, item.fileName)
        }.getOrDefault(false)) {
            cleanupSucceeded = false
        }
        val resolvedUri = runCatching {
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = item.localFileUri,
                destinationFileName = item.fileName,
            )
        }.getOrElse {
            cleanupSucceeded = false
            null
        }
        if (resolvedUri != null && !runCatching {
            DownloadsPlatformDownloader.removeFile(resolvedUri)
        }.getOrDefault(false)) {
            cleanupSucceeded = false
        }
        return cleanupSucceeded
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun markLocalFileUnavailable(item: DownloadItem) {
        val owner = ensureLoadedForCurrentProfile()
        mutateItem(owner, item.id) { current ->
            if (current.fileName == item.fileName) {
                current.markLocalFileUnavailable(DownloadsClock.nowEpochMs())
            } else {
                current
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        val owner = ensureLoadedForCurrentProfile()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(owner, item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        val owner = ensureLoadedForCurrentProfile()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        if (!isDownloadNetworkAllowedForCurrentSettings()) {
            return DownloadEnqueueResult.RequiresWifi
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            cancelDownloadOperation(owner, existing.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.id, existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist(owner)
        startDownload(item, owner)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        val owner = ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        cancelDownloadOperation(owner, downloadId)
        mutateItem(owner, downloadId) { current ->
            current.pauseIfNetworkDisallowed(
                networkAllowed = false,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String): DownloadEnqueueResult? {
        val owner = ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return null
        if (!isDownloadNetworkAllowedForCurrentSettings()) return DownloadEnqueueResult.RequiresWifi

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist(owner)
        startDownload(reset, owner)
        return DownloadEnqueueResult.Started
    }

    fun retryDownload(downloadId: String): DownloadEnqueueResult? = resumeDownload(downloadId)

    private fun isDownloadNetworkAllowedForCurrentSettings(): Boolean {
        DownloadsSettingsRepository.ensureLoaded()
        return isDownloadNetworkAllowed(
            allowMobileDataDownloads = DownloadsSettingsRepository.allowMobileDataDownloads.value,
            isOnWifi = DownloadNetworkGuard.isOnWifi(),
        )
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        val owner = ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        cancelDownloadOperation(owner, downloadId)
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist(owner)
        if (restored.status == DownloadStatus.Downloading) startDownload(restored, owner)
    }

    fun cancelDownload(downloadId: String) {
        val owner = ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        cancelDownloadOperation(owner, downloadId)
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.id, item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist(owner)
    }

    private fun loadFromDisk(owner: DownloadProfileOwner) {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        loadedProfileOwner = owner
        val payload = DownloadsStorage.loadPayload(owner.profileIndex).orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState(isLoaded = true)
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val operationKey = DownloadOperationKey(owner, item.id)
                val statusNormalized = if (operationKey in activeOperations) {
                    item
                } else {
                    DownloadsPlatformDownloader.restoreItem(item)
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(items = normalized, isLoaded = true)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) persist(owner)
        normalized
            .filter { it.status == DownloadStatus.Downloading }
            .filter { DownloadOperationKey(owner, it.id) !in activeOperations }
            .forEach { item -> startDownload(item, owner) }
    }

    private fun startDownload(item: DownloadItem, owner: DownloadProfileOwner) {
        DownloadsSettingsRepository.ensureLoaded()
        if (!isProfileOwnerCurrent(owner)) return
        val key = DownloadOperationKey(owner, item.id)
        if (key in activeOperations) return

        val generation = ++nextOperationGeneration
        activeOperations[key] = generation
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                if (isCurrentOperation(key, generation)) {
                    if (!isProfileOwnerCurrent(owner)) {
                        cancelDownloadOperation(owner, item.id)
                    } else if (!isDownloadNetworkAllowedForCurrentSettings()) {
                        // The preference can change or Wi-Fi can disappear after enqueue.
                        // Use the existing pause path so each platform preserves resumable bytes.
                        pauseDownload(item.id)
                    } else {
                        updateOwnedDownload(owner, item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) current
                            else current.copy(
                                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                                totalBytes = totalBytes?.takeIf { it > 0L },
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                                errorMessage = null,
                            )
                        }
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                if (isCurrentOperation(key, generation)) {
                    try {
                        updateOwnedDownload(owner, item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) current
                            else current.copy(
                                status = DownloadStatus.Completed,
                                localFileUri = localFileUri,
                                downloadedBytes = if (totalBytes != null && totalBytes > 0L) totalBytes
                                    else current.downloadedBytes,
                                totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                                errorMessage = null,
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            )
                        }
                    } finally {
                        finishDownloadOperation(key, generation)
                    }
                }
            },
            onFailure = { message ->
                if (isCurrentOperation(key, generation)) {
                    try {
                        updateOwnedDownload(owner, item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) current
                            else current.copy(
                                status = DownloadStatus.Failed,
                                errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            )
                        }
                    } finally {
                        finishDownloadOperation(key, generation)
                    }
                }
            },
            onPaused = {
                if (isCurrentOperation(key, generation)) {
                    try {
                        updateOwnedDownload(owner, item.id) { current ->
                            if (current.status != DownloadStatus.Downloading) current
                            else current.copy(status = DownloadStatus.Paused, errorMessage = null)
                        }
                    } finally {
                        finishDownloadOperation(key, generation)
                    }
                }
            },
        )

        if (isCurrentOperation(key, generation)) {
            activeHandles[key] = handle
            // Restored/background transfers may already be running before this handle attaches.
            // Pause only after the handle is registered so cancellation reaches the platform task.
            if (!isDownloadNetworkAllowedForCurrentSettings()) {
                pauseDownload(item.id)
            }
        } else {
            handle.cancel()
        }
    }

    private fun mutateItem(
        owner: DownloadProfileOwner,
        downloadId: String,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        updateOwnedDownload(owner, downloadId, transform)
    }

    private fun replaceItem(item: DownloadItem) {
        var previousStatus: DownloadStatus? = null
        val updated = updateProfileDownloadItem(_uiState.value.items, item.id) { current ->
            previousStatus = current.status
            item
        } ?: return
        publish(updated, isCompletedTransition(previousStatus, item.status))
    }

    private fun publish(items: List<DownloadItem>, newlyCompleted: Boolean = false) {
        if (newlyCompleted) _hasUnseenCompleted.value = true
        _uiState.value = DownloadsUiState(
            items = items,
            isLoaded = hasLoaded,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist(owner: DownloadProfileOwner) {
        if (loadedProfileOwner != owner || !isProfileOwnerCurrent(owner)) return
        DownloadsStorage.savePayload(
            profileIndex = owner.profileIndex,
            payload = DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

internal fun isCompletedTransition(previousStatus: DownloadStatus?, updatedStatus: DownloadStatus): Boolean =
    previousStatus != DownloadStatus.Completed && updatedStatus == DownloadStatus.Completed


internal data class DownloadProfileCleanupPlan(
    val operationKeys: List<DownloadOperationKey>,
    val items: List<DownloadItem>,
)

internal fun buildDownloadProfileCleanupPlan(
    profileIndex: Int,
    userId: String,
    activeOperationKeys: Collection<DownloadOperationKey>,
    persistedItems: Collection<DownloadItem>,
    visibleItems: Collection<DownloadItem>,
): DownloadProfileCleanupPlan =
    DownloadProfileCleanupPlan(
        operationKeys = activeOperationKeys.filter { key ->
            key.owner.profileIndex == profileIndex && key.owner.userId == userId
        },
        items = uniqueDownloadCleanupItems(visibleItems, persistedItems),
    )

internal fun uniqueDownloadCleanupItems(
    visibleItems: Collection<DownloadItem>,
    persistedItems: Collection<DownloadItem>,
): List<DownloadItem> =
    (visibleItems + persistedItems).distinctBy(DownloadItem::fileName)
