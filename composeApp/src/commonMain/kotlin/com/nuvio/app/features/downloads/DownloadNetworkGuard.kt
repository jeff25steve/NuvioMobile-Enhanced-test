package com.nuvio.app.features.downloads

/**
 * Snapshot of the device's current network transport, used to gate starting a download
 * when the user hasn't opted in to using mobile data for downloads.
 */
internal expect object DownloadNetworkGuard {
    /** True when the current connection is Wi-Fi (or no metered/cellular transport is active). */
    fun isOnWifi(): Boolean
}

internal fun isDownloadNetworkAllowed(
    allowMobileDataDownloads: Boolean,
    isOnWifi: Boolean,
): Boolean = allowMobileDataDownloads || isOnWifi


/** True when an active transfer should stop to honor the device's mobile-data preference. */
internal fun shouldPauseDownloadOnCurrentNetwork(
    allowMobileDataDownloads: Boolean,
    isOnWifi: Boolean,
): Boolean = !isDownloadNetworkAllowed(allowMobileDataDownloads, isOnWifi)
