package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProvider
import com.nuvio.app.features.debrid.DebridProviders
import kotlinx.coroutines.CancellationException

/**
 * Convert recoverable provider errors into Result while preserving coroutine cancellation.
 * A cancelled request is control flow, not a provider failure to display in the UI.
 */
internal suspend fun <T> cloudLibraryListResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal interface CloudLibraryProviderApi {
    val provider: DebridProvider

    suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>>

    suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult
}

internal object CloudLibraryProviderApis {
    private val registered = listOf(
        TorboxCloudLibraryProviderApi(),
        PremiumizeCloudLibraryProviderApi(),
    )

    fun all(): List<CloudLibraryProviderApi> = registered

    fun apiFor(providerId: String?): CloudLibraryProviderApi? {
        val normalized = DebridProviders.byId(providerId)?.id ?: return null
        return registered.firstOrNull { it.provider.id == normalized }
    }
}
