package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromString
import kotlinx.serialization.json.encodeToString

/**
 * Persists Supabase sessions in Android Keystore-backed encrypted preferences or iOS Keychain.
 *
 * The old Supabase multiplatform-settings entry is migrated lazily. Its plaintext copy is
 * removed only after the encrypted value has been written and successfully read back.
 */
internal class NativeSupabaseSessionManager(
    supabaseUrl: String,
    private val storage: SupabaseSessionStorage = createNativeSupabaseSessionStorage(),
) : SessionManager {

    private val storageKey = storageKeyFor(supabaseUrl)

    override suspend fun saveSession(session: UserSession) {
        val serialized = sessionJson.encodeToString(session)
        storage.writeEncrypted(storageKey, serialized)
        // A leftover legacy value is harmless to restoration (the encrypted value wins), but
        // should not remain in plaintext after a successful secure write.
        storage.deleteLegacy(storageKey)
    }

    override suspend fun loadSession(): UserSession {
        val encrypted = storage.readEncrypted(storageKey)
        if (encrypted != null) {
            if (encrypted == DELETED_SESSION_MARKER) {
                // A tombstone prevents an old legacy copy from resurrecting a signed-out session
                // if deletion of the legacy preference was interrupted.
                storage.deleteLegacy(storageKey)
                throw NoSessionFoundException()
            }
            val restored = sessionJson.decodeFromString<UserSession>(encrypted)
            // Retry cleanup if a previous save/migration could not remove the old plaintext copy.
            storage.deleteLegacy(storageKey)
            return restored
        }

        val legacy = storage.readLegacy(storageKey) ?: throw NoSessionFoundException()
        val migratedSession = sessionJson.decodeFromString<UserSession>(legacy)

        storage.writeEncrypted(storageKey, legacy)
        val encryptedCopy = storage.readEncrypted(storageKey)
            ?: error("Secure Supabase session migration did not persist a value")
        check(encryptedCopy != DELETED_SESSION_MARKER) {
            "Secure Supabase session migration was replaced before verification"
        }
        val verifiedSession = sessionJson.decodeFromString<UserSession>(encryptedCopy)
        check(verifiedSession == migratedSession) {
            "Secure Supabase session migration failed verification"
        }

        // Keep the original until the secure copy has been read back and decoded successfully.
        storage.deleteLegacy(storageKey)
        return verifiedSession
    }

    override suspend fun deleteSession() {
        // Persist the tombstone first. If legacy deletion fails or the process is interrupted,
        // the next load must never restore the old refresh token.
        storage.writeEncrypted(storageKey, DELETED_SESSION_MARKER)
        storage.deleteLegacy(storageKey)
    }

    internal companion object {
        const val DELETED_SESSION_MARKER = "nuvio:supabase-session-deleted:v1"

        val sessionJson = Json {
            // Supabase explicitly requires defaults to be encoded when persisting UserSession.
            encodeDefaults = true
        }

        fun storageKeyFor(supabaseUrl: String): String {
            val normalizedUrl = supabaseUrl.removeSuffix("/")
            require(normalizedUrl.isNotBlank()) { "Supabase URL must not be blank" }
            return "sb-${normalizedUrl.replace('/', '-').replace('.', '-')}-session"
        }
    }
}
