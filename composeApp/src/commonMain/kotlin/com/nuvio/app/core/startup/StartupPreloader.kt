package com.nuvio.app.core.startup

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private const val LogTag = "App/StartupPreload"
private const val SlowPreloadWarningMs = 6_000L

/** Coordinates work required before Compose appears with work that may wait for its first frame. */
internal class StartupPhaseCoordinator(
    private val scope: CoroutineScope,
    private val criticalPhase: suspend () -> Unit,
    private val postFramePhase: suspend () -> Unit,
) {
    private var criticalJob: Deferred<Unit>? = null
    private var postFrameStarted = false

    val isCriticalReady: Boolean
        get() = criticalJob?.isCompleted == true

    suspend fun awaitCriticalReady() {
        val active = criticalJob ?: scope.async { criticalPhase() }.also { criticalJob = it }
        active.await()
    }

    fun startPostFrameWarmup() {
        if (postFrameStarted) return
        postFrameStarted = true
        scope.launch {
            awaitCriticalReady()
            postFramePhase()
        }
    }
}

object StartupPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var profileCachePreloaded = false
    private val phases = StartupPhaseCoordinator(scope, ::preloadCriticalPhase, ::preloadPostFramePhase)

    val hasPreloadedProfileCache: Boolean
        get() = profileCachePreloaded
    val isCriticalReady: Boolean
        get() = phases.isCriticalReady

    suspend fun awaitCriticalReady() {
        if (withTimeoutOrNull(SlowPreloadWarningMs) { phases.awaitCriticalReady() } == null) {
            InAppLogger.warn(LogTag, "critical preload slower than ${SlowPreloadWarningMs}ms, still waiting")
            phases.awaitCriticalReady()
        }
    }

    fun startPostFrameWarmup() {
        phases.startPostFrameWarmup()
    }

    private suspend fun preloadCriticalPhase() {
        val started = TimeSource.Monotonic.markNow()
        coroutineScope {
            profileCachePreloaded = step("profiles") { ProfileRepository.loadCachedProfiles() }
            withContext(Dispatchers.IO) {
                step("watched") { WatchedRepository.ensureLoaded() }
                step("watchProgress") { WatchProgressRepository.ensureLoaded() }
                step("continueWatching") { ContinueWatchingPreferencesRepository.ensureLoaded() }
                step("homeSnapshot") { HomeRepository.preloadSnapshot() }
            }
        }
        InAppLogger.info(LogTag, "critical preload completed in ${started.elapsedNow().inWholeMilliseconds}ms")
    }

    private suspend fun preloadPostFramePhase() {
        val started = TimeSource.Monotonic.markNow()
        coroutineScope {
            val downloadsWarmup = async {
                withContext(Dispatchers.IO) {
                    step("downloadsPlatform") { DownloadsPlatformDownloader.prepareForStartup() }
                    step("downloadsTransferStore") { DownloadsPlatformDownloader.preloadTransferStore() }
                }
                step("downloadsRepository") { DownloadsRepository.ensureLoaded() }
            }
            withContext(Dispatchers.IO) {
                step("liveTv") { LiveTvRepository.ensureLoaded() }
                step("playerSettings") { PlayerSettingsRepository.ensureLoaded() }
                step("p2pSettings") { P2pSettingsRepository.ensureLoaded() }
                step("servers") { ServerRepository.ensureLoaded() }
                step("metaScreen") { MetaScreenSettingsRepository.ensureLoaded() }
                step("library") { LibraryRepository.ensureLoaded() }
            }
            downloadsWarmup.await()
        }
        InAppLogger.info(LogTag, "post-frame warmup completed in ${started.elapsedNow().inWholeMilliseconds}ms")
    }

    private inline fun <T> step(name: String, block: () -> T): Boolean =
        try {
            block()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            InAppLogger.warn(LogTag, "$name failed: ${error::class.simpleName}: ${error.message}")
            false
        }
}

internal fun shouldLoadProfileCacheFromAppGate(startupPreloadCompleted: Boolean): Boolean =
    !startupPreloadCompleted
