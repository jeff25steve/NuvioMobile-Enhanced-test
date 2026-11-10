package com.nuvio.app.features.trakt

import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraktAuthStorageTest {
    @Test
    fun legacyPlaintextPayloadMigratesToKeystoreEncryptedPreferences() {
        val context = RuntimeEnvironment.getApplication()
        TraktAuthStorage.initialize(context)
        val profileId = 1_905_110
        val key = ProfileScopedKey.of("trakt_auth_payload", profileId)
        val preferences = context.getSharedPreferences("nuvio_trakt_auth", Context.MODE_PRIVATE)
        val original = preferences.getString(key, null)
        val legacyPayload = """{"accessToken":"legacy-access","refreshToken":"legacy-refresh"}"""

        try {
            TraktAuthStorage.removeProfile(profileId)
            assertTrue(preferences.edit().putString(key, legacyPayload).commit())

            assertEquals(legacyPayload, TraktAuthStorage.loadPayload(profileId))
            assertNotEquals(legacyPayload, preferences.getString(key, null))
            assertEquals(legacyPayload, TraktAuthStorage.loadPayload(profileId))
        } finally {
            val editor = preferences.edit()
            if (original == null) editor.remove(key) else editor.putString(key, original)
            editor.commit()
        }
    }

    @Test
    fun encryptedPayloadsRemainProfileScopedAndCanBeRemoved() {
        val context = RuntimeEnvironment.getApplication()
        TraktAuthStorage.initialize(context)
        val first = 1_905_111
        val second = 1_905_112
        val firstKey = ProfileScopedKey.of("trakt_auth_payload", first)
        val secondKey = ProfileScopedKey.of("trakt_auth_payload", second)
        val preferences = context.getSharedPreferences("nuvio_trakt_auth", Context.MODE_PRIVATE)
        val originalFirst = preferences.getString(firstKey, null)
        val originalSecond = preferences.getString(secondKey, null)

        try {
            TraktAuthStorage.removeProfile(first)
            TraktAuthStorage.removeProfile(second)
            TraktAuthStorage.savePayload(first, "first-profile-token")
            TraktAuthStorage.savePayload(second, "second-profile-token")

            assertEquals("first-profile-token", TraktAuthStorage.loadPayload(first))
            assertEquals("second-profile-token", TraktAuthStorage.loadPayload(second))
            assertNotEquals("first-profile-token", preferences.getString(firstKey, null))

            TraktAuthStorage.removeProfile(first)
            assertNull(TraktAuthStorage.loadPayload(first))
            assertEquals("second-profile-token", TraktAuthStorage.loadPayload(second))
        } finally {
            val firstEditor = preferences.edit()
            if (originalFirst == null) firstEditor.remove(firstKey) else firstEditor.putString(firstKey, originalFirst)
            firstEditor.commit()
            val secondEditor = preferences.edit()
            if (originalSecond == null) secondEditor.remove(secondKey) else secondEditor.putString(secondKey, originalSecond)
            secondEditor.commit()
        }
    }
}
