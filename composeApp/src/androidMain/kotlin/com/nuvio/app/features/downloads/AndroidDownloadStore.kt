package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AndroidDownloadTransfer(
    val item: DownloadItem,
    val jobId: Int,
    val generation: String,
    val validator: String? = null,
    val retryCount: Int = 0,
    val exportTreeUri: String? = null,
    val partialDocumentUri: String? = null,
    val directWrite: Boolean = true,
)

internal class AndroidDownloadStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = KeyedSynchronizedLock()
    private val jobIdLock = Any()
    private var nextJobId: Long? = null
    private val state = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MutableStateFlow(load()) }
    private val stateFlow get() = state.value
    internal val isLoaded: Boolean get() = state.isInitialized()
    val transfers get() = stateFlow.asStateFlow()

    fun get(fileName: String): AndroidDownloadTransfer? = stateFlow.value[fileName]

    fun begin(item: DownloadItem, exportTreeUri: String? = null): AndroidDownloadTransfer =
        locks.withLock(item.fileName) {
            val previous = get(item.fileName)?.takeIf { it.item.id == item.id }
            if (previous?.item?.status == DownloadStatus.Downloading) return@withLock previous
            val transfer = AndroidDownloadTransfer(
                item = item.copy(status = DownloadStatus.Downloading, errorMessage = null, localFileUri = null),
                jobId = previous?.jobId ?: allocateJobId(),
                generation = UUID.randomUUID().toString(),
                validator = previous?.validator,
                exportTreeUri = exportTreeUri,
                partialDocumentUri = previous?.takeIf { it.exportTreeUri == exportTreeUri }?.partialDocumentUri,
                directWrite = previous?.takeIf { it.exportTreeUri == exportTreeUri }?.directWrite ?: true,
            )
            save(transfer)
            transfer
        }

    fun pauseIfDownloading(fileName: String, generation: String): AndroidDownloadTransfer? =
        locks.withLock(fileName) {
            val current = get(fileName)?.takeIf { it.generation == generation }
                ?: return@withLock null
            if (current.item.status != DownloadStatus.Downloading) return@withLock null

            current.copy(
                item = current.item.copy(status = DownloadStatus.Paused, errorMessage = null),
            ).also(::save)
        }

    fun update(
        fileName: String,
        generation: String,
        transform: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = locks.withLock(fileName) {
        val current = get(fileName)?.takeIf { it.generation == generation } ?: return@withLock null
        transform(current).also(::save)
    }

    fun remove(fileName: String): AndroidDownloadTransfer? = locks.withLock(fileName) {
        // Initialize the snapshot before touching the record so the first lazy load cannot
        // race with this deletion and restore a record that has already been removed.
        stateFlow.value
        val current = get(fileName) ?: return@withLock null
        removeCurrent(fileName)
        current
    }

    fun remove(fileName: String, generation: String): AndroidDownloadTransfer? = locks.withLock(fileName) {
        stateFlow.value
        val current = get(fileName)?.takeIf { it.generation == generation }
            ?: return@withLock null
        removeCurrent(fileName)
        current
    }

    private fun removeCurrent(fileName: String) {
        AtomicFile(recordFile(fileName)).delete()
        stateFlow.update { current -> current - fileName }
    }

    private fun allocateJobId(): Int = synchronized(jobIdLock) {
        val next = nextJobId ?: ((stateFlow.value.values.maxOfOrNull { it.jobId }?.toLong() ?: 0L) + 1L)
        check(next <= Int.MAX_VALUE) { "Download job ID space exhausted" }
        nextJobId = next + 1L
        next.toInt()
    }

    private fun save(transfer: AndroidDownloadTransfer) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download state directory" }
        val file = AtomicFile(recordFile(transfer.item.fileName))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(transfer).toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        stateFlow.update { current -> current + (transfer.item.fileName to transfer) }
    }

    private fun load(): Map<String, AndroidDownloadTransfer> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }
        .distinct()
        .mapNotNull { file ->
            runCatching {
                json.decodeFromString<AndroidDownloadTransfer>(AtomicFile(file).readFully().decodeToString())
            }.getOrNull()
        }.associateBy { it.item.fileName }

    private fun recordFile(fileName: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(fileName.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
}
