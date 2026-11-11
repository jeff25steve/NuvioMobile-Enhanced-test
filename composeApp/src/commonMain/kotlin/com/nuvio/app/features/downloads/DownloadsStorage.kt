package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(profileIndex: Int): String?
    fun savePayload(profileIndex: Int, payload: String)
    fun getDownloadLocationUri(): String?
    fun setDownloadLocationUri(uri: String?)
}
