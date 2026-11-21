package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
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
import kotlinx.serialization.json.decodeFromJsonElement
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
    private val lastProgressPersistAt = mutableMapOf<String, Long>()

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

    /** Returns persisted offline metadata used to hydrate downloaded Library entries. */
    fun getOfflineMetadata(showId: String): StoredOfflineMeta? = OfflineMetadataRepository[showId]

    /**
     * Resets in-memory state on sign-out. Must not write storage: by the time this runs the
     * active profile has already been reset, so a write would land on another profile's key.
     * Unfinished records for every profile are removed by the platform storage wipe.
     */
    fun clearLocalState() {
        val handles = activeHandles.values.toList()
        activeOperations.clear()
        activeHandles.clear()
        handles.forEach { handle -> runCatching { handle.cancel() } }
        hasLoaded = false
        loadedProfileOwner = null
        _hasUnseenCompleted.value = false
        OfflineMetadataRepository.clear()
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
        episodeOverview: String? = null,
        showMeta: MetaDetails? = null,
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

        val replacement = replaceExistingDownload(logicalKey)
        val replacedExisting = replacement != null
        val currentItems = _uiState.value.items.toMutableList()
        replacement?.let { previous -> currentItems.removeAll { it.id == previous.id } }
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

        val queuedItem = DownloadItem(
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
            status = DownloadStatus.Queued,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        currentItems.add(0, queuedItem)
        publish(currentItems)
        updateOfflineMetadata(
            meta = showMeta,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            videoId = videoId,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            episodeOverview = episodeOverview,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        persist(owner)
        advanceDownloadQueue()
        return when (_uiState.value.items.firstOrNull { it.id == queuedItem.id }?.status) {
            DownloadStatus.Queued -> DownloadEnqueueResult.Queued
            DownloadStatus.Downloading -> if (replacedExisting) DownloadEnqueueResult.Replaced else DownloadEnqueueResult.Started
            else -> if (replacedExisting) DownloadEnqueueResult.Replaced else DownloadEnqueueResult.Queued
        }
    }

    private fun updateOfflineMetadata(
        meta: MetaDetails?, parentMetaId: String, parentMetaType: String, title: String,
        logo: String?, poster: String?, background: String?, videoId: String, episodeTitle: String?,
        episodeThumbnail: String?, episodeOverview: String?, seasonNumber: Int?, episodeNumber: Int?,
    ) {
        if (meta != null) {
            OfflineMetadataRepository.merge(meta)
        } else {
            OfflineMetadataRepository.recordBasic(
                showId = parentMetaId, type = parentMetaType, title = title, logo = logo,
                poster = poster, background = background,
                episode = if (seasonNumber != null && episodeNumber != null) StoredDownloadedVideoMeta(
                    id = videoId, title = episodeTitle.orEmpty(), thumbnail = episodeThumbnail,
                    season = seasonNumber, episode = episodeNumber, overview = episodeOverview,
                ) else null,
            )
        }
    }

    private fun replaceExistingDownload(logicalKey: String): DownloadItem? {
        val existing = _uiState.value.items.firstOrNull { it.logicalContentKey == logicalKey } ?: return null
        dropHandle(existing.id)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(existing.id, existing.fileName)
        return existing
    }

    /** Starts queued downloads until the concurrency limits or network policy stop it. */
    fun advanceDownloadQueue() {
        val owner = ensureLoadedForCurrentProfile()
        val policy = DownloadQueuePolicy.current()
        val items = _uiState.value.items.toMutableList()
        val started = mutableListOf<DownloadItem>()
        while (true) {
            val next = policy.nextToStart(items) ?: break
            val replacement = next.copy(status = DownloadStatus.Downloading, updatedAtEpochMs = DownloadsClock.nowEpochMs(), errorMessage = null)
            val index = items.indexOfFirst { it.id == next.id }
            if (index < 0) break
            items[index] = replacement
            started += replacement
        }
        if (started.isNotEmpty()) {
            publish(items)
            persist(owner)
            started.forEach { item -> startDownload(item, owner) }
        }
    }

    fun pauseDownload(downloadId: String) {
        pauseDownloads(listOf(downloadId))
    }

    fun pauseActiveDownloads() {
        pauseActiveDownloadsMatching { true }
    }

    /** Pauses the download if it is running or queued; otherwise resumes or retries it. */
    fun togglePause(downloadId: String) {
        val owner = ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.isActive) pauseDownload(downloadId) else resumeDownload(downloadId)
    }

    /** Pauses every matching active item in one update before advancing the queue. */
    private fun pauseActiveDownloadsMatching(predicate: (DownloadItem) -> Boolean) {
        ensureLoadedForCurrentProfile()
        val current = _uiState.value.items
        val targets = current.filter { it.isActive && predicate(it) }
        if (targets.isEmpty()) return
        val ids = targets.mapTo(mutableSetOf()) { it.id }
        setStatus(ids, DownloadStatus.Paused)
        advanceDownloadQueue()
    }

    private fun setStatus(ids: Set<String>, status: DownloadStatus) {
        if (ids.isEmpty()) return
        val owner = ensureLoadedForCurrentProfile()
        val current = _uiState.value.items
        ids.forEach { id -> dropHandle(id)?.cancel() }
        val now = DownloadsClock.nowEpochMs()
        publish(current.map { item ->
            if (item.id in ids) item.copy(status = status, updatedAtEpochMs = now, errorMessage = null) else item
        })
        persist(owner)
    }

    /** Pauses queued or running downloads by ID in one update. */
    fun pauseDownloads(downloadIds: Collection<String>) {
        val ids = downloadIds.toSet()
        if (ids.isEmpty()) return
        pauseActiveDownloadsMatching { it.id in ids }
    }

    /** Resumes paused episodes of [seriesId] and retries its failed ones. */
    private fun resumeOrRetrySeriesDownloads(seriesId: String) {
        ensureLoaded()
        _uiState.value.items
            .filter { it.parentMetaId == seriesId && it.isResumable }
            .map { it.id }
            .let(::resumeDownloads)
    }

    /** Resumes every paused download and retries every failed one. */
    fun resumeOrRetryAllDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.isResumable }
            .map { it.id }
            .let(::resumeDownloads)
    }

    fun resumePausedDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Paused }
            .map { it.id }
            .let(::resumeDownloads)
    }

    /** Re-queues resumable items under the active profile, then applies the configured queue policy. */
    fun resumeDownloads(downloadIds: Collection<String>) {
        val owner = ensureLoadedForCurrentProfile()
        val ids = downloadIds.toSet()
        if (ids.isEmpty()) return
        val current = _uiState.value.items
        val now = DownloadsClock.nowEpochMs()
        val updated = current.map { item ->
            if (item.id in ids && item.isResumable) {
                item.copy(
                    status = DownloadStatus.Queued,
                    errorMessage = null,
                    localFileUri = null,
                    updatedAtEpochMs = now,
                )
            } else item
        }
        if (updated == current) return
        publish(updated)
        persist(owner)
        advanceDownloadQueue()
    }

    fun resumeDownload(downloadId: String): DownloadEnqueueResult? {
        ensureLoadedForCurrentProfile()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        if (!item.isResumable) return null
        if (!isDownloadNetworkAllowedForCurrentSettings()) return DownloadEnqueueResult.RequiresWifi
        resumeDownloads(listOf(downloadId))
        return when (_uiState.value.items.firstOrNull { it.id == downloadId }?.status) {
            DownloadStatus.Downloading -> DownloadEnqueueResult.Started
            DownloadStatus.Queued -> DownloadEnqueueResult.Queued
            else -> null
        }
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
        cancelDownloads(listOf(downloadId))
    }

    /** Removes matching downloads and updates profile storage once. */
    fun cancelDownloads(downloadIds: Collection<String>) {
        val owner = ensureLoadedForCurrentProfile()
        val ids = downloadIds.toSet()
        if (ids.isEmpty()) return
        val current = _uiState.value.items
        val targets = current.filter { it.id in ids }
        if (targets.isEmpty()) return
        val shouldAdvanceQueue = targets.any { it.isActive }
        targets.forEach { item ->
            cancelDownloadOperation(owner, item.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.id, item.fileName)
        }
        publish(current.filterNot { it.id in ids })
        persist(owner)
        if (shouldAdvanceQueue) advanceDownloadQueue()
    }

    private fun loadFromDisk(owner: DownloadProfileOwner) {
        OfflineMetadataRepository.reload()
        _hasUnseenCompleted.value = false
        hasLoaded = true
        loadedProfileOwner = owner
        val payload = DownloadsStorage.loadPayload(owner.profileIndex).orEmpty().trim()
        if (payload.isEmpty()) {
            OfflineMetadataRepository.ensureLoaded()
            _uiState.value = DownloadsUiState(isLoaded = true)
            OfflineMetadataRepository.setDownloads(emptyList())
            notifyLiveStatusPlatform()
            return
        }

        val decoded = DownloadsCodec.decodePayload(payload)
        OfflineMetadataRepository.migrateLegacy(decoded.showMetadata)

        var shouldPersistNormalized = false
        val normalized = decoded.items
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
        advanceDownloadQueue()
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
                    advanceDownloadQueue()
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
                    advanceDownloadQueue()
                }
            },
            onPaused = {
                if (isCurrentOperation(key, generation)) {
                    try {
                        updateOwnedDownload(owner, item.id) { current ->
                            if (current.status != DownloadStatus.Downloading && current.status != DownloadStatus.Queued) current
                            else current.copy(status = DownloadStatus.Paused, errorMessage = null)
                        }
                    } finally {
                        finishDownloadOperation(key, generation)
                    }
                    advanceDownloadQueue()
                }
            },
            onQueued = {
                if (isCurrentOperation(key, generation)) {
                    updateOwnedDownload(owner, item.id) { current ->
                        if (current.status == DownloadStatus.Completed) current
                        else current.copy(status = DownloadStatus.Queued, errorMessage = null)
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

    /** Forgets a transfer's handle and its progress-save timestamp. */
    private fun dropHandle(downloadId: String): DownloadsTaskHandle? {
        val owner = loadedProfileOwner ?: return null
        val key = DownloadOperationKey(owner, downloadId)
        lastProgressPersistAt.remove(downloadId)
        activeOperations.remove(key)
        return activeHandles.remove(key)
    }

    private fun shouldPersistProgress(item: DownloadItem): Boolean {
        val now = DownloadsClock.nowEpochMs()
        val previous = lastProgressPersistAt[item.id] ?: 0L
        if (now - previous < PROGRESS_PERSIST_INTERVAL_MS) return false
        lastProgressPersistAt[item.id] = now
        return true
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
        OfflineMetadataRepository.setDownloads(items)
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        loadedProfileOwner?.let(::persist)
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
}

internal fun DownloadItem.hasPlayableLocalFile(): Boolean =
    status == DownloadStatus.Completed &&
        DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = localFileUri,
            destinationFileName = fileName,
        ) != null

@Serializable
internal data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    val showMetadata: Map<String, StoredOfflineMeta> = emptyMap(),
)

@Serializable
private data class StoredDownloadItemsPayload(val items: List<DownloadItem> = emptyList())

internal object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> = decodePayload(payload).items

    fun encodeItems(items: Collection<DownloadItem>): String = encodePayload(items)

    fun decodePayload(payload: String): StoredDownloadsPayload = runCatching {
        val root = json.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject
            ?: return StoredDownloadsPayload()
        val items = (root["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { element -> runCatching { json.decodeFromJsonElement<DownloadItem>(element) }.getOrNull() }
        val metadata = runCatching {
            root["showMetadata"]?.let { json.decodeFromJsonElement<Map<String, StoredOfflineMeta>>(it) }.orEmpty()
        }.getOrDefault(emptyMap())
        StoredDownloadsPayload(items = items, showMetadata = metadata)
    }.getOrDefault(StoredDownloadsPayload())

    fun encodePayload(items: Collection<DownloadItem>): String =
        json.encodeToString(StoredDownloadItemsPayload(items = items.toList()))
}

private const val PROGRESS_PERSIST_INTERVAL_MS = 5_000L

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

internal fun buildLogicalKey(
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

internal fun String.isSupportedDownloadUrl(): Boolean {
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
