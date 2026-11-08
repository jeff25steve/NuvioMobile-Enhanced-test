package com.nuvio.app.features.downloads

internal data class DownloadContentRange(
    val firstByte: Long,
    val lastByteInclusive: Long,
    val completeLength: Long?,
) {
    val byteCount: Long
        get() = lastByteInclusive - firstByte + 1L
}

internal fun parseDownloadContentRange(value: String): DownloadContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val totalText = match.groupValues[3]
    val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
    if (end < start || end - start == Long.MAX_VALUE || (total != null && end >= total)) return null
    return DownloadContentRange(
        firstByte = start,
        lastByteInclusive = end,
        completeLength = total,
    )
}
