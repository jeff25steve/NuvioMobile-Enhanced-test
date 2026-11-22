package com.nuvio.app.features.downloads

import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidDownloadStoreGenerationTest {
    @Test
    fun staleGenerationCannotRemoveReplacementTransfer() {
        val directory = Files.createTempDirectory("nuvio-download-store-generation-remove-test").toFile()
        try {
            val store = AndroidDownloadStore(directory)
            val original = store.begin(downloadItem())
            store.pauseIfDownloading(original.item.fileName, original.generation)
            val replacement = store.begin(original.item)

            assertFalse(original.generation == replacement.generation)
            assertEquals(null, store.remove(original.item.fileName, original.generation))
            assertEquals(replacement, store.get(original.item.fileName))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun matchingGenerationCanRemoveItsTransfer() {
        val directory = Files.createTempDirectory("nuvio-download-store-generation-remove-current-test").toFile()
        try {
            val store = AndroidDownloadStore(directory)
            val transfer = store.begin(downloadItem())

            assertEquals(transfer, store.remove(transfer.item.fileName, transfer.generation))
            assertEquals(null, store.get(transfer.item.fileName))
        } finally {
            directory.deleteRecursively()
        }
    }
}
