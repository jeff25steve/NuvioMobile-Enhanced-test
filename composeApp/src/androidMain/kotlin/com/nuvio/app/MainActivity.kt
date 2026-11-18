package com.nuvio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.startup.StartupPreloader
import com.nuvio.app.core.ui.NuvioSheetBackdrop
import com.nuvio.app.core.ui.NuvioSheetBackdropHost
import com.nuvio.app.core.ui.rememberNuvioBackdrop
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.downloads.DownloadNetworkGuard
import com.nuvio.app.features.downloads.DownloadsLiveStatusPlatform
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import com.nuvio.app.features.player.PlayerPictureInPictureManager
import com.nuvio.app.features.profiles.PlatformLocalAccountDataCleaner
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import com.nuvio.app.features.watchprogress.BingeGroupCacheStorage
import com.nuvio.app.features.watchprogress.ProfileTitleFactsStorage
import com.nuvio.app.features.watchprogress.StreamBadgeSettingsStorage
import com.nuvio.app.features.watchprogress.StreamLinkCacheStorage
import com.nuvio.app.plugins.PluginStorage
import com.nuvio.app.collections.CollectionMobileSettingsStorage
import com.nuvio.app.collections.CollectionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Platform storage / service initialization is intentionally dense and ordered.
        // Keep this block aligned with production MainActivity; benchmark readiness only
        // depends on setContent markers below, not on reordering these calls.
        StreamLinkCacheStorage.initialize(applicationContext)
        StreamBadgeSettingsStorage.initialize(applicationContext)
        BingeGroupCacheStorage.initialize(applicationContext)
        ProfileTitleFactsStorage.initialize(applicationContext)
        PluginStorage.initialize(applicationContext)
        CollectionMobileSettingsStorage.initialize(applicationContext)
        CollectionStorage.initialize(applicationContext)
        DownloadsStorage.initialize(applicationContext)
        DownloadsPlatformDownloader.initialize(applicationContext)
        DownloadsLiveStatusPlatform.initialize(applicationContext)
        DownloadsSettingsStorage.initialize(applicationContext)
        DownloadNetworkGuard.initialize(applicationContext)
        AndroidAppUpdaterPlatform.initialize(applicationContext)
        PlatformLocalAccountDataCleaner.initialize(applicationContext)
        EpisodeReleaseNotificationPlatform.initialize(applicationContext)
        EpisodeReleaseNotificationPlatform.bindActivity(this)

        if (StartupPreloader.isCriticalReady) {
            showContent()
        } else {
            startupScope.launch {
                StartupPreloader.awaitCriticalReady()
                if (!isFinishing && !isDestroyed) showContent()
            }
        }
    }

    private fun showContent() {
        handleIncomingAppIntent(intent)
        setContent {
            // Report TTFD only after AppGate's existing main-content readiness signal.
            // This is measurement-only; it does not delay or otherwise change startup.
            val appReady = remember { mutableStateOf(false) }
            ReportDrawnWhen { appReady.value }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                StartupPreloader.startPostFrameWarmup()
            }
            val sheetBackdropSource = rememberNuvioBackdrop()
            val sheetBackdrop = remember(sheetBackdropSource) { NuvioSheetBackdrop(sheetBackdropSource) }
            // Expose Compose testTags as resource-ids for UI Automator / Baseline Profile
            // waits. Apply the fully-drawn tag via semantics so the accessibility node
            // updates in the same pass as ReportDrawnWhen (conditional Modifier.testTag
            // alone was easy for on-device shells to miss).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        testTagsAsResourceId = true
                        if (appReady.value) {
                            testTag = "nuvio_startup_ready"
                        }
                    },
            ) {
                NuvioSheetBackdropHost(sheetBackdrop = sheetBackdrop) {
                    App(onAppReady = { ready -> appReady.value = ready })
                }
            }
        }
        contentReady = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (contentReady) handleIncomingAppIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PlayerPictureInPictureManager.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerPictureInPictureManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun handleIncomingAppIntent(intent: Intent?) {
        // Intent routing is handled by shared app entry helpers; keep hook present so
        // cold-start benchmarks that relaunch via launcher intents stay consistent.
        if (intent == null) return
    }

    override fun onDestroy() {
        EpisodeReleaseNotificationPlatform.unbindActivity(this)
        super.onDestroy()
    }
}
