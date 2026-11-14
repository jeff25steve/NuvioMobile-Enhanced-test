package com.nuvio.app.features.downloads

import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDownloadStoreTest {
    @Test
    fun constructionDoesNotReadPersistedTransfersUntilFirstAccess() {
        val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-download-store-lazy-test")
        directory.deleteRecursively()
        directory.mkdirs()
        try {
            val store = AndroidDownloadStore(directory)

            assertFalse(store.isLoaded, "The store should not scan transfer records during construction")

            assertTrue(store.transfers.value.isEmpty())
            assertTrue(store.isLoaded, "The first transfer-state access should load persisted records")
        } finally {
            directory.deleteRecursively()
        }
    }
}
