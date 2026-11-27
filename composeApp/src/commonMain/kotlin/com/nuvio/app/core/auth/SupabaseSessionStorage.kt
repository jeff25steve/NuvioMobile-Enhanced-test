package com.nuvio.app.core.auth

/**
 * Platform-backed storage for Supabase sessions.
 *
 * Implementations must distinguish an absent item (null) from storage, decryption,
 * keychain-access and corruption failures (exceptions). Legacy access is only used
 * for the one-time migration from Supabase's previous multiplatform-settings key.
 */
internal interface SupabaseSessionStorage {
    suspend fun readEncrypted(key: String): String?

    suspend fun writeEncrypted(key: String, value: String)

    suspend fun deleteEncrypted(key: String)

    suspend fun readLegacy(key: String): String?

    suspend fun deleteLegacy(key: String)
}

internal expect fun createNativeSupabaseSessionStorage(): SupabaseSessionStorage
