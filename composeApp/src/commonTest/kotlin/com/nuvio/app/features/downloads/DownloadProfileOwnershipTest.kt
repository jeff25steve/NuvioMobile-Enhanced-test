package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DownloadProfileOwnershipTest {
    @Test
    fun profileKeySeparatesTheSameDownloadIdAcrossAccounts() {
        val first = DownloadOperationKey(
            owner = DownloadProfileOwner(2, "account-one", "profile-two"),
            downloadId = "download-123",
        )
        val second = DownloadOperationKey(
            owner = DownloadProfileOwner(2, "account-two", "profile-two"),
            downloadId = "download-123",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun profileKeySeparatesADeletedAndRecreatedProfileAtTheSameIndex() {
        val deleted = DownloadOperationKey(
            owner = DownloadProfileOwner(3, "account-one", "old-profile-id"),
            downloadId = "download-123",
        )
        val recreated = DownloadOperationKey(
            owner = DownloadProfileOwner(3, "account-one", "new-profile-id"),
            downloadId = "download-123",
        )

        assertNotEquals(deleted, recreated)
    }

    @Test
    fun profileCleanupPlanKeepsOnlyOperationsOwnedByTheDeletedAccountAndIndex() {
        val deletedProfileOperation = DownloadOperationKey(
            owner = DownloadProfileOwner(2, "account-one", "old-profile-id"),
            downloadId = "download-old",
        )
        val plan = buildDownloadProfileCleanupPlan(
            profileIndex = 2,
            userId = "account-one",
            activeOperationKeys = listOf(
                deletedProfileOperation,
                DownloadOperationKey(
                    owner = DownloadProfileOwner(2, "account-two", "profile-two"),
                    downloadId = "other-account",
                ),
                DownloadOperationKey(
                    owner = DownloadProfileOwner(3, "account-one", "profile-three"),
                    downloadId = "other-profile",
                ),
            ),
            persistedItems = listOf(download("persisted")),
            visibleItems = emptyList(),
        )

        assertEquals(listOf(deletedProfileOperation), plan.operationKeys)
    }

    @Test
    fun profileCleanupPlanIncludesPersistedAndVisibleItemsOncePerDestination() {
        val persisted = download("saved").copy(fileName = "saved.mp4")
        val visibleDuplicate = download("visible-copy").copy(fileName = "shared.mp4")
        val persistedDuplicate = download("persisted-copy").copy(fileName = "shared.mp4")
        val visibleOnly = download("visible").copy(fileName = "visible.mp4")

        val plan = buildDownloadProfileCleanupPlan(
            profileIndex = 2,
            userId = "account-one",
            activeOperationKeys = emptyList(),
            persistedItems = listOf(persisted, persistedDuplicate),
            visibleItems = listOf(visibleDuplicate, visibleOnly),
        )

        assertEquals(
            listOf("shared.mp4", "visible.mp4", "saved.mp4"),
            plan.items.map(DownloadItem::fileName),
        )
    }

    @Test
    fun updateChangesOnlyTheMatchingDownloadAndPreservesListOrder() {
        val first = download("first")
        val target = download("target")
        val last = download("last")

        val updated = updateProfileDownloadItem(
            items = listOf(first, target, last),
            downloadId = "target",
        ) {
            it.copy(
                status = DownloadStatus.Completed,
                downloadedBytes = 42L,
                localFileUri = "file:///downloads/target.mp4",
            )
        }

        assertEquals(
            listOf(
                first,
                target.copy(
                    status = DownloadStatus.Completed,
                    downloadedBytes = 42L,
                    localFileUri = "file:///downloads/target.mp4",
                ),
                last,
            ),
            updated,
        )
    }

    @Test
    fun updateReturnsNullWhenDownloadIsAbsentOrAlreadyUnchanged() {
        val item = download("present")

        assertNull(updateProfileDownloadItem(listOf(item), "missing") { it })
        assertNull(updateProfileDownloadItem(listOf(item), "present") { it })
    }

    private fun download(id: String) = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = "tmdb:100",
        parentMetaType = "movie",
        videoId = "tmdb:100",
        title = "Example",
        streamTitle = "Example stream",
        providerName = "Example provider",
        sourceUrl = "https://media.example/video.mp4",
        fileName = "$id.mp4",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
