package com.nuvio.app.features.downloads

import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDownloadStoreTest {
    @Test
    fun constructionDoesNotReadPersistedTransfersUntilFirstAccess() {
        val directory = Files.createTempDirectory("nuvio-download-store-lazy-test").toFile()
        try {
            val store = AndroidDownloadStore(directory)

            assertFalse(store.isLoaded, "The store should not scan transfer records during construction")

            assertTrue(store.transfers.value.isEmpty())
            assertTrue(store.isLoaded, "The first transfer-state access should load persisted records")
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun firstAccessRestoresPersistedTransferRecords() {
        val directory = Files.createTempDirectory("nuvio-download-store-restore-test").toFile()
        try {
            val item = downloadItem()
            AndroidDownloadStore(directory).begin(item)

            val restoredStore = AndroidDownloadStore(directory)
            assertFalse(restoredStore.isLoaded)

            val restored = restoredStore.get(item.fileName)
            assertEquals(item.id, restored?.item?.id)
            assertEquals(DownloadStatus.Downloading, restored?.item?.status)
            assertTrue(restoredStore.isLoaded)
        } finally {
            directory.deleteRecursively()
        }
    }

}
