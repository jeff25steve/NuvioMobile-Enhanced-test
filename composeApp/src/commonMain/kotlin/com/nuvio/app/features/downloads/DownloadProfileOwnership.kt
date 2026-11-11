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
