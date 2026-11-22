package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun updatesForDifferentFilesDoNotBlockEachOther() {
        val directory = Files.createTempDirectory("nuvio-download-store-independent-test").toFile()
        try {
            val store = AndroidDownloadStore(directory)
            val first = store.begin(downloadItem(id = "first").copy(fileName = "first.mkv"))
            val second = store.begin(downloadItem(id = "second").copy(fileName = "second.mkv"))
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()

            fun updateInThread(transfer: AndroidDownloadTransfer) = Thread {
                try {
                    store.update(transfer.item.fileName, transfer.generation) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release store update" }
                        it.copy(retryCount = it.retryCount + 1)
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }

            val firstThread = updateInThread(first)
            val secondThread = updateInThread(second)
            firstThread.start()
            secondThread.start()

            try {
                assertTrue(
                    entered.await(2, TimeUnit.SECONDS),
                    "An update for one file must not hold the store-wide lock while another file is updated",
                )
            } finally {
                release.countDown()
            }

            firstThread.join(2_000)
            secondThread.join(2_000)
            assertFalse(firstThread.isAlive)
            assertFalse(secondThread.isAlive)
            assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
            assertEquals(1, store.get(first.item.fileName)?.retryCount)
            assertEquals(1, store.get(second.item.fileName)?.retryCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun updatesForTheSameFileRemainSerialized() {
        val directory = Files.createTempDirectory("nuvio-download-store-same-file-test").toFile()
        try {
            val store = AndroidDownloadStore(directory)
            val transfer = store.begin(downloadItem())
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondAttempting = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()

            val firstThread = Thread {
                try {
                    store.update(transfer.item.fileName, transfer.generation) {
                        firstEntered.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                        it.copy(retryCount = 1)
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }
            firstThread.start()
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

            val secondThread = Thread {
                try {
                    secondAttempting.countDown()
                    store.update(transfer.item.fileName, transfer.generation) {
                        secondEntered.countDown()
                        it.copy(retryCount = it.retryCount + 1)
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }
            secondThread.start()

            try {
                assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (secondThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, secondThread.state)
                assertEquals(1L, secondEntered.count)
            } finally {
                releaseFirst.countDown()
            }

            firstThread.join(2_000)
            secondThread.join(2_000)
            assertFalse(firstThread.isAlive)
            assertFalse(secondThread.isAlive)
            assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
            assertEquals(2, store.get(transfer.item.fileName)?.retryCount)
        } finally {
            directory.deleteRecursively()
        }
    }
}
