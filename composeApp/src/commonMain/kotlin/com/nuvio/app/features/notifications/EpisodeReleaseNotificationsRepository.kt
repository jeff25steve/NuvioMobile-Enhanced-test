package com.nuvio.app.features.notifications

import co.touchlab.kermit.Logger
import com.nuvio.app.core.concurrency.runCatchingUnlessCancelled
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.serialization.json.Json

object EpisodeReleaseNotificationsRepository {
    private const val metadataFetchConcurrency = 4
    private const val testNotificationDelaySeconds = 1L

    private val log = Logger.withTag("EpisodeReleaseNotifications")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val refreshMutex = Mutex()
    private val platformOperationsMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private var profileGeneration = 0
    private var accountCleanupInProgress = false
    private var loadedToken: EpisodeReleaseNotificationProfileToken? = null

    private val _uiState = MutableStateFlow(EpisodeReleaseNotificationsUiState())
    val uiState: StateFlow<EpisodeReleaseNotificationsUiState> = _uiState.asStateFlow()

    private var trackedShowsByKey: Map<String, TrackedFollowedShow> = emptyMap()
    private var cachedRequestsByShowKey: Map<String, List<EpisodeReleaseNotificationRequest>> = emptyMap()

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in refreshRequests) {
                runCatchingUnlessCancelled { refreshScheduledNotifications() }
                    .onFailure { error ->
                        log.e(error) { "Failed to refresh episode release notifications" }
                    }
            }
        }
        scope.launch {
            LibraryRepository.uiState.collectLatest { state ->
                val token = synchronized(stateLock) {
                    loadedToken?.takeIf(::isCurrentOperationLocked)
                } ?: return@collectLatest

                val changed = reconcileTrackedShows(state, token)
                if (changed) {
                    persist(token)
                    if (isCurrentOperation(token) && _uiState.value.isEnabled) {
                        requestRefresh()
                    }
                }
            }
        }
    }

    private fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    fun ensureLoaded() {
        ensureLoadedToken()
    }

    private fun ensureLoadedToken(): EpisodeReleaseNotificationProfileToken? {
        if (synchronized(stateLock) { accountCleanupInProgress }) return null
        val profileId = ProfileRepository.activeProfileId
        synchronized(stateLock) {
            loadedToken?.takeIf { it.profileId == profileId && isCurrentOperationLocked(it) }
        }?.let { return it }

        val token = beginProfileOperation(profileId)
        if (!loadFromDisk(token)) return null
        scope.launch {
            syncAuthorizationState(token, refreshIfEnabled = true)
        }
        return token.takeIf(::isCurrentOperation)
    }

    fun onProfileChanged(profileId: Int) {
        if (ProfileRepository.activeProfileId != profileId) return
        val token = beginProfileOperation(profileId)
        if (!loadFromDisk(token)) return
        scope.launch {
            syncAuthorizationState(token, refreshIfEnabled = true)
        }
    }

    fun clearLocalState() {
        synchronized(stateLock) {
            accountCleanupInProgress = true
            profileGeneration += 1
            loadedToken = null
            trackedShowsByKey = emptyMap()
            cachedRequestsByShowKey = emptyMap()
            _uiState.value = EpisodeReleaseNotificationsUiState()
        }
    }

    fun onAccountCleanupFinished() {
        synchronized(stateLock) {
            accountCleanupInProgress = false
        }
    }

    suspend fun clearPlatformNotificationsForAccountWipe() {
        platformOperationsMutex.withLock {
            try {
                EpisodeReleaseNotificationPlatform.clearAllEpisodeReleaseNotifications()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error) { "Failed to clear episode release notifications during account cleanup" }
                throw error
            }
        }
    }

    private fun beginProfileOperation(profileId: Int): EpisodeReleaseNotificationProfileToken =
        synchronized(stateLock) {
            profileGeneration += 1
            loadedToken = null
            trackedShowsByKey = emptyMap()
            cachedRequestsByShowKey = emptyMap()
            EpisodeReleaseNotificationProfileToken(profileId, profileGeneration)
        }

    private fun isCurrentOperation(token: EpisodeReleaseNotificationProfileToken): Boolean =
        synchronized(stateLock) { isCurrentOperationLocked(token) }

    private fun isCurrentOperationLocked(token: EpisodeReleaseNotificationProfileToken): Boolean =
        !accountCleanupInProgress &&
            isEpisodeReleaseNotificationOperationCurrent(
                token = token,
                activeProfileId = ProfileRepository.activeProfileId,
                currentGeneration = profileGeneration,
            )

    private fun isSchedulingAllowed(token: EpisodeReleaseNotificationProfileToken): Boolean =
        synchronized(stateLock) {
            isEpisodeReleaseNotificationSchedulingAllowed(
                token = token,
                activeProfileId = ProfileRepository.activeProfileId,
                currentGeneration = profileGeneration,
                isEnabled = _uiState.value.isEnabled,
                accountCleanupInProgress = accountCleanupInProgress,
            )
        }

    private fun updateUiState(
        token: EpisodeReleaseNotificationProfileToken,
        transform: (EpisodeReleaseNotificationsUiState) -> EpisodeReleaseNotificationsUiState,
    ): Boolean = synchronized(stateLock) {
        if (!isCurrentOperationLocked(token)) return@synchronized false
        _uiState.value = transform(_uiState.value)
        true
    }

    private suspend fun clearProfileNotifications(token: EpisodeReleaseNotificationProfileToken) {
        platformOperationsMutex.withLock {
            if (!isCurrentOperation(token)) return@withLock
            EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications(token.profileId)
        }
    }

    internal fun applyFromSyncEnabled(enabled: Boolean) {
        val token = ensureLoadedToken() ?: return
        var changed = false
        if (!updateUiState(token) { state ->
                if (state.isEnabled == enabled) {
                    state
                } else {
                    changed = true
                    state.copy(
                        isEnabled = enabled,
                        isLoading = false,
                        isSendingTest = false,
                        statusMessage = null,
                        errorMessage = null,
                    )
                }
            }) return
        if (!changed) return

        persist(token)
        requestRefresh()
    }

    fun setEnabled(enabled: Boolean) {
        val token = ensureLoadedToken() ?: return
        scope.launch {
            if (!isCurrentOperation(token)) return@launch
            if (!enabled) {
                if (!updateUiState(token) {
                        it.copy(
                            isEnabled = false,
                            isLoading = false,
                            scheduledCount = 0,
                            statusMessage = null,
                            errorMessage = null,
                        )
                    }) return@launch
                persist(token)
                runCatchingUnlessCancelled { clearProfileNotifications(token) }
                    .onFailure { error ->
                        log.w { "Failed to clear episode release notifications: ${error.message}" }
                    }
                return@launch
            }

            if (!updateUiState(token) { it.copy(isLoading = true, errorMessage = null) }) return@launch

            val granted = runCatchingUnlessCancelled { EpisodeReleaseNotificationPlatform.requestAuthorization() }
                .onFailure { error ->
                    log.e(error) { "Failed to request episode release notification permission" }
                }
                .getOrDefault(false)
            if (!isCurrentOperation(token)) return@launch

            if (!granted) {
                val permissionError = getString(Res.string.settings_notifications_permission_disabled)
                updateUiState(token) {
                    it.copy(
                        isEnabled = false,
                        isLoading = false,
                        permissionGranted = false,
                        scheduledCount = 0,
                        statusMessage = null,
                        errorMessage = permissionError,
                    )
                }
                persist(token)
                return@launch
            }

            if (!updateUiState(token) {
                    it.copy(
                        isEnabled = true,
                        isLoading = false,
                        permissionGranted = true,
                        statusMessage = null,
                        errorMessage = null,
                    )
                }) return@launch
            persist(token)
            refreshScheduledNotifications(token)
        }
    }

    fun sendTestNotification() {
        val token = ensureLoadedToken() ?: return
        scope.launch {
            val target = currentTestTarget()
            if (!isCurrentOperation(token)) return@launch
            if (target == null) {
                val targetError = getString(Res.string.settings_notifications_test_requires_saved_show)
                updateUiState(token) {
                    it.copy(
                        isSendingTest = false,
                        statusMessage = null,
                        errorMessage = targetError,
                    )
                }
                return@launch
            }

            if (!updateUiState(token) {
                    it.copy(isSendingTest = true, statusMessage = null, errorMessage = null)
                }) return@launch

            val granted = runCatchingUnlessCancelled { EpisodeReleaseNotificationPlatform.requestAuthorization() }
                .onFailure { error ->
                    log.e(error) { "Failed to request permission for test notification" }
                }
                .getOrDefault(false)
            if (!isCurrentOperation(token)) return@launch

            if (!granted) {
                val permissionError = getString(Res.string.settings_notifications_permission_disabled)
                updateUiState(token) {
                    it.copy(
                        isSendingTest = false,
                        permissionGranted = false,
                        statusMessage = null,
                        errorMessage = permissionError,
                    )
                }
                return@launch
            }

            val request = EpisodeReleaseNotificationRequest(
                requestId = "${EpisodeReleaseNotificationIdentifierPrefix}test-${token.profileId}-${EpisodeReleaseDatePlatform.nowEpochMs()}",
                notificationTitle = target.name,
                notificationBody = getString(Res.string.notifications_test_preview_body),
                releaseDateIso = CurrentDateProvider.todayIsoDate(),
                deepLinkUrl = buildMetaDeepLinkUrl(type = target.type, id = target.id),
                backdropUrl = target.banner ?: target.poster,
            )

            val sendResult = runCatchingUnlessCancelled {
                platformOperationsMutex.withLock {
                    if (isCurrentOperation(token)) {
                        EpisodeReleaseNotificationPlatform.showTestNotification(request)
                    }
                }
            }
            sendResult.exceptionOrNull()?.let { error ->
                log.e(error) { "Failed to send test notification" }
            }
            if (!isCurrentOperation(token)) return@launch

            if (sendResult.isSuccess) {
                val statusMessage = getString(Res.string.notifications_test_sent_for, target.name)
                updateUiState(token) {
                    it.copy(
                        isSendingTest = false,
                        permissionGranted = true,
                        statusMessage = statusMessage,
                        errorMessage = null,
                    )
                }
            } else {
                val errorMessage = getString(Res.string.notifications_test_send_failed)
                updateUiState(token) {
                    it.copy(
                        isSendingTest = false,
                        permissionGranted = true,
                        statusMessage = null,
                        errorMessage = errorMessage,
                    )
                }
            }
        }
    }

    fun refreshAsync() {
        if (ensureLoadedToken() != null) requestRefresh()
    }

    private fun loadFromDisk(token: EpisodeReleaseNotificationProfileToken): Boolean {
        if (!isCurrentOperation(token)) return false
        val payload = EpisodeReleaseNotificationsStorage.loadPayload(token.profileId).orEmpty().trim()
        val stored = payload.takeIf { it.isNotEmpty() }
            ?.let { rawPayload ->
                runCatching {
                    json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(rawPayload)
                }.onFailure { error ->
                    log.w { "Failed to decode episode release notifications payload: ${error.message}" }
                }.getOrNull()
            }

        val loaded = synchronized(stateLock) {
            if (!isCurrentOperationLocked(token)) return@synchronized false
            trackedShowsByKey = buildMap {
                stored?.followedShows.orEmpty().forEach { trackedShow ->
                    put(buildTrackedShowKey(trackedShow.contentType, trackedShow.contentId), trackedShow)
                }
            }
            cachedRequestsByShowKey = stored?.cachedRequests.orEmpty()
            loadedToken = token
            _uiState.value = EpisodeReleaseNotificationsUiState(
                isEnabled = stored?.enabled ?: false,
                permissionGranted = false,
                scheduledCount = 0,
                testTargetTitle = null,
                errorMessage = null,
            )
            true
        }
        if (!loaded) return false
        updateTestTargetState(token)
        return isCurrentOperation(token)
    }

    private fun persist(token: EpisodeReleaseNotificationProfileToken) {
        val payload: String? = synchronized(stateLock) {
            if (!isCurrentOperationLocked(token)) return@synchronized null
            json.encodeToString(
                StoredEpisodeReleaseNotificationsPayload(
                    enabled = _uiState.value.isEnabled,
                    followedShows = trackedShowsByKey.values
                        .sortedWith(compareBy(TrackedFollowedShow::contentType, TrackedFollowedShow::contentId)),
                    cachedRequests = cachedRequestsByShowKey,
                ),
            )
        } ?: return

        synchronized(stateLock) {
            if (isCurrentOperationLocked(token)) {
                EpisodeReleaseNotificationsStorage.savePayload(token.profileId, payload)
            }
        }
    }

    private suspend fun syncAuthorizationState(
        token: EpisodeReleaseNotificationProfileToken,
        refreshIfEnabled: Boolean,
    ) {
        val granted = runCatchingUnlessCancelled { EpisodeReleaseNotificationPlatform.notificationsAuthorized() }
            .onFailure { error ->
                log.w { "Failed to read episode release notification permission: ${error.message}" }
            }
            .getOrDefault(false)
        if (!isCurrentOperation(token)) return

        val testTargetTitle = currentTestTarget()?.name
        val permissionError = if (!granted) {
            getString(Res.string.settings_notifications_permission_disabled)
        } else {
            null
        }
        updateUiState(token) { state ->
            state.copy(
                permissionGranted = granted,
                testTargetTitle = testTargetTitle,
                errorMessage = if (state.isEnabled && !granted) permissionError else state.errorMessage,
            )
        }

        if (refreshIfEnabled && isCurrentOperation(token) && _uiState.value.isEnabled) {
            refreshScheduledNotifications(token)
        }
    }

    private fun reconcileTrackedShows(
        state: LibraryUiState,
        token: EpisodeReleaseNotificationProfileToken,
    ): Boolean {
        if (!state.isLoaded) return false

        val seriesItems = state.items.filter { item -> isSeriesLibraryType(item.type) }
        val changed = synchronized(stateLock) {
            if (!isCurrentOperationLocked(token)) return@synchronized false
            val nextTrackedShows = linkedMapOf<String, TrackedFollowedShow>()

            seriesItems.forEach { item ->
                val key = buildTrackedShowKey(item.type, item.id)
                nextTrackedShows[key] = trackedShowsByKey[key]
                    ?: TrackedFollowedShow(
                        contentId = item.id,
                        contentType = item.type,
                        followedOnIsoDate = inferFollowedOnIsoDate(item),
                    )
            }

            val didChange = nextTrackedShows != trackedShowsByKey
            if (didChange) trackedShowsByKey = nextTrackedShows.toMap()
            didChange
        }
        if (isCurrentOperation(token)) updateTestTargetState(token)
        return changed
    }

    private fun inferFollowedOnIsoDate(item: LibraryItem): String {
        if (item.savedAtEpochMs >= MinReasonableSavedAtEpochMs) {
            return EpisodeReleaseNotificationsClock.isoDateFromEpochMs(item.savedAtEpochMs)
        }
        return CurrentDateProvider.todayIsoDate()
    }

    private suspend fun refreshScheduledNotifications(
        requestedToken: EpisodeReleaseNotificationProfileToken? = null,
    ) {
        refreshMutex.withLock {
            val token = requestedToken ?: synchronized(stateLock) {
                loadedToken?.takeIf(::isCurrentOperationLocked)
            } ?: return@withLock
            if (!isCurrentOperation(token)) return@withLock

            LibraryRepository.ensureLoaded()
            if (!isCurrentOperation(token)) return@withLock

            val trackedShowsChanged = reconcileTrackedShows(LibraryRepository.uiState.value, token)
            if (trackedShowsChanged) persist(token)
            if (!isCurrentOperation(token)) return@withLock

            val permissionGranted = runCatchingUnlessCancelled {
                EpisodeReleaseNotificationPlatform.notificationsAuthorized()
            }.onFailure { error ->
                log.w { "Failed to refresh episode release notification permission: ${error.message}" }
            }.getOrDefault(false)
            if (!isCurrentOperation(token)) return@withLock

            if (!_uiState.value.isEnabled || !permissionGranted) {
                runCatchingUnlessCancelled { clearProfileNotifications(token) }
                    .onFailure { error ->
                        log.w { "Failed to clear scheduled episode release notifications: ${error.message}" }
                    }
                if (!isCurrentOperation(token)) return@withLock
                val testTargetTitle = currentTestTarget()?.name
                val permissionError = if (!permissionGranted) {
                    getString(Res.string.settings_notifications_permission_disabled)
                } else {
                    null
                }
                updateUiState(token) { state ->
                    state.copy(
                        isLoading = false,
                        permissionGranted = permissionGranted,
                        scheduledCount = 0,
                        testTargetTitle = testTargetTitle,
                        errorMessage = if (state.isEnabled && !permissionGranted) permissionError else null,
                    )
                }
                return@withLock
            }

            if (!updateUiState(token) {
                    it.copy(isLoading = true, permissionGranted = true, errorMessage = null)
                }) return@withLock

            val snapshots = synchronized(stateLock) {
                if (!isCurrentOperationLocked(token)) return@synchronized null
                trackedShowsByKey to cachedRequestsByShowKey
            } ?: return@withLock

            if (snapshots.first.isEmpty()) {
                runCatchingUnlessCancelled { clearProfileNotifications(token) }
                if (!isCurrentOperation(token)) return@withLock
                val testTargetTitle = currentTestTarget()?.name
                updateUiState(token) {
                    it.copy(
                        isLoading = false,
                        scheduledCount = 0,
                        testTargetTitle = testTargetTitle,
                        errorMessage = null,
                    )
                }
                return@withLock
            }

            AddonRepository.initialize()
            withTimeoutOrNull(10_000L) {
                AddonRepository.awaitManifestsLoaded()
            }
            if (!isCurrentOperation(token)) return@withLock

            val todayIso = CurrentDateProvider.todayIsoDate()
            val semaphore = Semaphore(metadataFetchConcurrency)
            val requestsByShowKey = coroutineScope {
                snapshots.first.map { (showKey, trackedShow) ->
                    async {
                        val built = semaphore.withPermit {
                            buildRequestsForShow(trackedShow, token.profileId)
                        }
                        val showRequests = (built ?: snapshots.second[showKey].orEmpty())
                            .filter { request -> request.releaseDateIso >= todayIso }
                        showKey to showRequests
                    }
                }.awaitAll().toMap()
            }
            if (!isCurrentOperation(token)) return@withLock

            val cachedUpdated = synchronized(stateLock) {
                if (!isCurrentOperationLocked(token)) return@synchronized false
                cachedRequestsByShowKey = requestsByShowKey
                true
            }
            if (!cachedUpdated) return@withLock
            persist(token)

            val requests = requestsByShowKey.values
                .flatten()
                .distinctBy(EpisodeReleaseNotificationRequest::requestId)
                .sortedBy(EpisodeReleaseNotificationRequest::releaseDateIso)

            runCatchingUnlessCancelled {
                platformOperationsMutex.withLock {
                    if (isSchedulingAllowed(token)) {
                        EpisodeReleaseNotificationPlatform.scheduleEpisodeReleaseNotifications(
                            profileId = token.profileId,
                            requests = requests,
                        )
                    }
                }
            }.onFailure { error ->
                log.e(error) { "Failed to schedule episode release notifications" }
            }
            if (!isSchedulingAllowed(token)) return@withLock

            val testTargetTitle = currentTestTarget()?.name
            updateUiState(token) { state ->
                if (!state.isEnabled) {
                    state.copy(isLoading = false, scheduledCount = 0, errorMessage = null)
                } else {
                    state.copy(
                        isLoading = false,
                        permissionGranted = true,
                        scheduledCount = requests.size,
                        testTargetTitle = testTargetTitle,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    private fun updateTestTargetState(token: EpisodeReleaseNotificationProfileToken) {
        if (!isCurrentOperation(token)) return
        val title = currentTestTarget()?.name
        updateUiState(token) { it.copy(testTargetTitle = title) }
    }

    private fun currentTestTarget(): LibraryItem? {
        LibraryRepository.ensureLoaded()
        val libraryItems = LibraryRepository.uiState.value.items
        return libraryItems.firstOrNull { item -> isSeriesLibraryType(item.type) }
            ?: libraryItems.firstOrNull()
    }

    private suspend fun buildRequestsForShow(
        trackedShow: TrackedFollowedShow,
        profileId: Int,
    ): List<EpisodeReleaseNotificationRequest>? {
        val meta = runCatchingUnlessCancelled {
            MetaDetailsRepository.fetch(
                type = trackedShow.contentType,
                id = trackedShow.contentId,
                cacheResult = false,
            )
        }.onFailure { error ->
            log.w { "Failed to resolve metadata for ${trackedShow.contentType}:${trackedShow.contentId}: ${error.message}" }
        }.getOrNull() ?: return null

        val showTitle = meta.name.ifBlank { trackedShow.contentId }
        return meta.videos.mapNotNull { episode ->
            val releaseDate = releaseDateIso(episode.released) ?: return@mapNotNull null
            if (releaseDate < trackedShow.followedOnIsoDate) return@mapNotNull null
            if (episode.season == null && episode.episode == null) return@mapNotNull null

            EpisodeReleaseNotificationRequest(
                requestId = buildEpisodeReleaseNotificationId(
                    profileId = profileId,
                    contentType = trackedShow.contentType,
                    contentId = trackedShow.contentId,
                    episodeId = episode.id,
                    releaseDateIso = releaseDate,
                ),
                notificationTitle = showTitle,
                notificationBody = buildEpisodeReleaseNotificationBody(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                    episodeTitle = episode.title,
                ),
                releaseDateIso = releaseDate,
                deepLinkUrl = buildMetaDeepLinkUrl(
                    type = trackedShow.contentType,
                    id = trackedShow.contentId,
                ),
                backdropUrl = meta.background ?: episode.thumbnail ?: episode.seasonPoster ?: meta.poster,
            )
        }
    }
}
