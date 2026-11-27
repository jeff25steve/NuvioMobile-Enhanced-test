package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeSupabaseSessionManagerTest {

    @Test
    fun missingSessionUsesExpectedSignedOutState() {
        val manager = NativeSupabaseSessionManager(
            supabaseUrl = "https://project.example",
            storage = FakeSupabaseSessionStorage(),
        )

        assertFailsWith<NoSessionFoundException> {
            kotlinx.coroutines.test.runTest { manager.loadSession() }
        }
    }

    @Test
    fun legacySessionMigratesOnlyAfterEncryptedCopyIsVerified() = kotlinx.coroutines.test.runTest {
        val storage = FakeSupabaseSessionStorage()
        val expected = testSession()
        storage.legacyValue = NativeSupabaseSessionManager.sessionJson.encodeToString(expected)
        val manager = NativeSupabaseSessionManager("https://project.example", storage)

        val restored = manager.loadSession()

        assertEquals(expected, restored)
        assertNull(storage.legacyValue)
        assertNotNull(storage.encryptedValue)
    }

    @Test
    fun failedMigrationKeepsLegacySessionIntact() = kotlinx.coroutines.test.runTest {
        val storage = FakeSupabaseSessionStorage().apply {
            legacyValue = NativeSupabaseSessionManager.sessionJson.encodeToString(testSession())
            failWrites = true
        }
        val manager = NativeSupabaseSessionManager("https://project.example", storage)

        assertFailsWith<IllegalStateException> { manager.loadSession() }
        assertNotNull(storage.legacyValue)
        assertNull(storage.encryptedValue)
    }

    @Test
    fun unreadableEncryptedSessionNeverFallsBackToLegacyCopy() = kotlinx.coroutines.test.runTest {
        val storage = FakeSupabaseSessionStorage().apply {
            encryptedValue = "{corrupt"
            legacyValue = NativeSupabaseSessionManager.sessionJson.encodeToString(testSession())
        }
        val manager = NativeSupabaseSessionManager("https://project.example", storage)

        assertFailsWith<IllegalArgumentException> { manager.loadSession() }
        assertNotNull(storage.legacyValue)
    }

    @Test
    fun deleteWritesTombstoneBeforeRemovingLegacySession() = kotlinx.coroutines.test.runTest {
        val storage = FakeSupabaseSessionStorage().apply {
            legacyValue = NativeSupabaseSessionManager.sessionJson.encodeToString(testSession())
        }
        val manager = NativeSupabaseSessionManager("https://project.example", storage)

        manager.deleteSession()

        assertEquals(NativeSupabaseSessionManager.DELETED_SESSION_MARKER, storage.encryptedValue)
        assertNull(storage.legacyValue)
        assertFailsWith<NoSessionFoundException> { manager.loadSession() }
    }

    @Test
    fun interruptedLegacyDeletionCannotResurrectSignedOutSession() = kotlinx.coroutines.test.runTest {
        val storage = FakeSupabaseSessionStorage().apply {
            legacyValue = NativeSupabaseSessionManager.sessionJson.encodeToString(testSession())
            failLegacyDelete = true
        }
        val manager = NativeSupabaseSessionManager("https://project.example", storage)

        assertFailsWith<IllegalStateException> { manager.deleteSession() }
        assertEquals(NativeSupabaseSessionManager.DELETED_SESSION_MARKER, storage.encryptedValue)
        assertNotNull(storage.legacyValue)
        storage.failLegacyDelete = false
        assertFailsWith<NoSessionFoundException> { manager.loadSession() }
    }

    @Test
    fun backendUrlsUseSeparateStorageKeys() {
        val first = NativeSupabaseSessionManager.storageKeyFor("https://one.example/")
        val second = NativeSupabaseSessionManager.storageKeyFor("https://two.example/")

        kotlin.test.assertNotEquals(first, second)
    }

    private fun testSession() = UserSession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        expiresIn = 3600,
        tokenType = "bearer",
        expiresAt = Instant.fromEpochMilliseconds(1_900_000_000_000),
    )

    private class FakeSupabaseSessionStorage : SupabaseSessionStorage {
        var encryptedValue: String? = null
        var legacyValue: String? = null
        var failWrites = false
        var failLegacyDelete = false

        override suspend fun readEncrypted(key: String): String? = encryptedValue

        override suspend fun writeEncrypted(key: String, value: String) {
            check(!failWrites) { "simulated secure-storage write failure" }
            encryptedValue = value
        }

        override suspend fun readLegacy(key: String): String? = legacyValue

        override suspend fun deleteLegacy(key: String) {
            check(!failLegacyDelete) { "simulated legacy cleanup failure" }
            legacyValue = null
        }
    }
}
