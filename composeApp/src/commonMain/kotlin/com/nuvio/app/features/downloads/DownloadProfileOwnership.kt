package com.nuvio.app.features.downloads

internal data class DownloadProfileOwner(
    val profileIndex: Int,
    val userId: String?,
    val profileId: String?,
)

internal data class DownloadOperationKey(
    val owner: DownloadProfileOwner,
    val downloadId: String,
)

/**
 * Replaces one download in an immutable profile snapshot.
 *
 * Returns null when the download is absent or the transform leaves it unchanged.
 * The caller owns profile validation and persistence.
 */
internal fun updateProfileDownloadItem(
    items: List<DownloadItem>,
    downloadId: String,
    transform: (DownloadItem) -> DownloadItem,
): List<DownloadItem>? {
    val index = items.indexOfFirst { it.id == downloadId }
    if (index < 0) return null

    val current = items[index]
    val updated = transform(current)
    if (updated == current) return null

    return items.toMutableList().also { it[index] = updated }
}


/**
 * Turns a completed item with an unavailable local file into a retryable download.
 * The original source and file name remain intact so the normal retry path can fetch it again.
 */
internal fun DownloadItem.markLocalFileUnavailable(updatedAtEpochMs: Long): DownloadItem =
    if (status != DownloadStatus.Completed) {
        this
    } else {
        copy(
            status = DownloadStatus.Failed,
            localFileUri = null,
            errorMessage = null,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }
