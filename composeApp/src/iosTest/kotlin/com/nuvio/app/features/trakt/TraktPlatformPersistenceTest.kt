package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraktPlatformPersistenceTest {
    @Test
    fun legacyUserDefaultsPayloadMigratesToDeviceOnlyKeychain() {
        val profileId = 1_905_120
        val legacyKey = ProfileScopedKey.of("trakt_auth_payload", profileId)
        val defaults = NSUserDefaults.standardUserDefaults
        val originalKeychain = TraktAuthStorage.loadPayload(profileId)
        val originalLegacy = defaults.stringForKey(legacyKey)
        val legacyPayload = """{"accessToken":"legacy-access","refreshToken":"legacy-refresh"}"""

        try {
            TraktAuthStorage.removeProfile(profileId)
            defaults.setObject(legacyPayload, forKey = legacyKey)

            assertEquals(legacyPayload, TraktAuthStorage.loadPayload(profileId))
            assertNull(defaults.stringForKey(legacyKey))
            TraktAuthStorage.savePayload(profileId, "rotated-profile-token")
            assertEquals("rotated-profile-token", TraktAuthStorage.loadPayload(profileId))
        } finally {
            TraktAuthStorage.removeProfile(profileId)
            originalKeychain?.let { TraktAuthStorage.savePayload(profileId, it) }
            originalLegacy?.let { defaults.setObject(it, forKey = legacyKey) }
        }
    }

    @Test
    fun removingTraktCredentialsDoesNotAffectAnotherProfile() {
        val first = 1_905_121
        val second = 1_905_122
        val originalFirst = TraktAuthStorage.loadPayload(first)
        val originalSecond = TraktAuthStorage.loadPayload(second)

        try {
            TraktAuthStorage.removeProfile(first)
            TraktAuthStorage.removeProfile(second)
            TraktAuthStorage.savePayload(first, "first-profile-token")
            TraktAuthStorage.savePayload(second, "second-profile-token")

            TraktAuthStorage.removeProfile(first)
            assertNull(TraktAuthStorage.loadPayload(first))
            assertEquals("second-profile-token", TraktAuthStorage.loadPayload(second))
        } finally {
            TraktAuthStorage.removeProfile(first)
            TraktAuthStorage.removeProfile(second)
            originalFirst?.let { TraktAuthStorage.savePayload(first, it) }
            originalSecond?.let { TraktAuthStorage.savePayload(second, it) }
        }
    }
}
