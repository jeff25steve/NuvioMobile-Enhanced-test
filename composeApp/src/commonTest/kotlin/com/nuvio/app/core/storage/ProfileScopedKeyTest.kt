package com.nuvio.app.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProfileScopedKeyTest {
    @Test
    fun explicitProfileIdsProduceDistinctCredentialKeys() {
        val firstProfileKey = ProfileScopedKey.of("simkl_access_token", 1)
        val secondProfileKey = ProfileScopedKey.of("simkl_access_token", 2)

        assertEquals("simkl_access_token_1", firstProfileKey)
        assertEquals("simkl_access_token_2", secondProfileKey)
        assertNotEquals(firstProfileKey, secondProfileKey)
    }

    @Test
    fun profileRemovalKeyUsesTheRequestedProfileRatherThanCurrentSelection() {
        assertEquals("simkl_auth_metadata_7", ProfileScopedKey.of("simkl_auth_metadata", 7))
    }
}
