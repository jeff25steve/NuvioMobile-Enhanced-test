package com.nuvio.app.core.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalAccountDataCleanerTest {
    private lateinit var preferences: SharedPreferences

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("nuvio_cw_enrichment", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        ContinueWatchingEnrichmentStorage.initialize(context)
        PlatformLocalAccountDataCleaner.initialize(context)
        ContinueWatchingEnrichmentCache.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        ContinueWatchingEnrichmentCache.clearLocalState()
        preferences.edit().clear().commit()
    }

    @Test
    fun profileBackgroundCleanupAttemptsBothImagesForEveryProfileAfterRemovalFailure() {
        val attemptedSettingsProfiles = mutableListOf<Int>()
        val attemptedCloudProfiles = mutableListOf<Int>()

        val allRemoved = clearProfileBackgroundImages(
            profileIndices = 1..MAX_PROFILES,
            removeSettingsImage = { profileIndex ->
                attemptedSettingsProfiles += profileIndex
                profileIndex != 2
            },
            removeCloudImage = { profileIndex ->
                attemptedCloudProfiles += profileIndex
                profileIndex != 3
            },
        )

        assertEquals((1..MAX_PROFILES).toList(), attemptedSettingsProfiles)
        assertEquals((1..MAX_PROFILES).toList(), attemptedCloudProfiles)
        assertFalse(allRemoved)
    }

    @Test
    fun profileBackgroundCleanupContinuesBothImageTypesWhenRemovalThrows() {
        val attemptedSettingsProfiles = mutableListOf<Int>()
        val attemptedCloudProfiles = mutableListOf<Int>()

        val allRemoved = clearProfileBackgroundImages(
            profileIndices = 1..MAX_PROFILES,
            removeSettingsImage = { profileIndex ->
                attemptedSettingsProfiles += profileIndex
                check(profileIndex != 2) { "simulated Settings background removal failure" }
                true
            },
            removeCloudImage = { profileIndex ->
                attemptedCloudProfiles += profileIndex
                check(profileIndex != 3) { "simulated Cloud Library background removal failure" }
                true
            },
        )

        assertEquals((1..MAX_PROFILES).toList(), attemptedSettingsProfiles)
        assertEquals((1..MAX_PROFILES).toList(), attemptedCloudProfiles)
        assertFalse(allRemoved)
    }

    @Test
    fun storageWipeClearsSnapshotsReloadedDuringAccountCleanup() {
        val initialGeneration = ContinueWatchingEnrichmentCache.generation.value
        assertTrue(save("old account", initialGeneration))
        ContinueWatchingEnrichmentCache.clearLocalState()
        val cleanupGeneration = ContinueWatchingEnrichmentCache.generation.value

        LocalAccountDataCleaner.wipePlatformStorage {
            assertEquals("old account", snapshots().single().name)
            PlatformLocalAccountDataCleaner.wipe()
        }

        assertTrue(snapshots().isEmpty())
        assertFalse(save("old resolver", initialGeneration))
        assertFalse(save("cleanup resolver", cleanupGeneration))
        assertTrue(save("new account", ContinueWatchingEnrichmentCache.generation.value))
        assertEquals("new account", snapshots().single().name)
    }

    @Test
    fun failedStorageWipeStillInvalidatesReloadedSnapshotsAndResolverGenerations() {
        assertTrue(save("old account", ContinueWatchingEnrichmentCache.generation.value))
        ContinueWatchingEnrichmentCache.clearLocalState()
        val cleanupGeneration = ContinueWatchingEnrichmentCache.generation.value
        val failure = IllegalStateException("Remaining storage cleanup failed")

        val thrown = assertFailsWith<IllegalStateException> {
            LocalAccountDataCleaner.wipePlatformStorage {
                assertEquals("old account", snapshots().single().name)
                PlatformLocalAccountDataCleaner.wipe()
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertTrue(snapshots().isEmpty())
        assertFalse(save("cleanup resolver", cleanupGeneration))
    }

    @Test
    fun accountCleanupAttemptsEveryStepAfterEarlierFailures() = runBlocking {
        val attempts = mutableListOf<Int>()
        val failures = runAccountCleanupSteps(
            listOf(
                {
                    attempts += 1
                    error("simulated notification cleanup failure")
                },
                {
                    attempts += 2
                },
                {
                    attempts += 3
                    error("simulated cache cleanup failure")
                },
            ),
        )

        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(2, failures.size)
    }

    @Test
    fun accountCleanupRethrowsCancellationAfterAttemptingRemainingSteps() = runBlocking {
        val cancellation = CancellationException("simulated cleanup cancellation")
        val attempts = mutableListOf<Int>()
        val failures = runAccountCleanupSteps(
            listOf(
                {
                    attempts += 1
                    throw cancellation
                },
                {
                    attempts += 2
                    error("later cleanup failure")
                },
            ),
        )

        assertEquals(listOf(1, 2), attempts)
        val thrown = assertFailsWith<CancellationException> {
            throwAccountCleanupFailures(failures)
        }
        assertSame(cancellation, thrown)
        assertEquals(1, thrown.suppressed.size)
    }

    @Test
    fun mandatoryCleanupFinishesBeforeCallerCancellationIsPropagated() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()
        val cleanup = launch {
            runNonCancellableCleanup {
                cleanupStarted.complete(Unit)
                allowCleanupToFinish.await()
            }
        }

        cleanupStarted.await()
        cleanup.cancel()
        assertFalse(cleanup.isCompleted)

        allowCleanupToFinish.complete(Unit)
        cleanup.join()

        assertTrue(cleanup.isCancelled)
    }

    @Test
    fun failedPluginGateAcquisitionReleasesAlreadyAcquiredAddonGate() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("plugin gate acquisition failed")

        val thrown = assertFailsWith<IllegalStateException> {
            acquireAccountCleanupGates(
                beginAddon = { events += "begin-addon" },
                endAddon = { events += "end-addon" },
                beginPlugin = { events += "begin-plugin"; throw failure },
                endPlugin = { events += "end-plugin" },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("begin-addon", "begin-plugin", "end-addon"), events)
    }

    @Test
    fun acquiredAccountCleanupGatesReleaseInReverseOrder() {
        val events = mutableListOf<String>()
        val release = acquireAccountCleanupGates(
            beginAddon = { events += "begin-addon" },
            endAddon = { events += "end-addon" },
            beginPlugin = { events += "begin-plugin" },
            endPlugin = { events += "end-plugin" },
        )

        release()

        assertEquals(
            listOf("begin-addon", "begin-plugin", "end-plugin", "end-addon"),
            events,
        )
    }

    private fun snapshots(): List<CachedNextUpItem> =
        ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL)

    private fun save(name: String, generation: Int): Boolean = ContinueWatchingEnrichmentCache.saveSnapshots(
        profileId = 1,
        source = WatchProgressSource.SIMKL,
        generation = generation,
        nextUp = listOf(
            CachedNextUpItem(
                contentId = "show",
                contentType = "series",
                name = name,
                videoId = "show:1:2",
                lastWatched = 1L,
                sortTimestamp = 2L,
            ),
        ),
        inProgress = emptyList(),
    )
}
