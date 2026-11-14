package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklProfileReloadStateTest {
    @Test
    fun profileReloadAlwaysClearsThePreviousProfilesLoadingAndErrorState() {
        val restored = simklAuthStateAfterProfileReload(
            storedState = SimklStoredAuthState(username = "new-profile"),
            accessToken = null,
            credentialsConfigured = true,
        )

        assertEquals(SimklConnectionMode.DISCONNECTED, restored.mode)
        assertEquals("new-profile", restored.username)
        assertFalse(restored.isLoading)
        assertEquals(null, restored.error)
    }

    @Test
    fun profileReloadRestoresAuthenticatedModeWithoutRestoringOldLoadingState() {
        val restored = simklAuthStateAfterProfileReload(
            storedState = SimklStoredAuthState(username = "account", accountId = 42L),
            accessToken = "profile-token",
            credentialsConfigured = true,
        )

        assertEquals(SimklConnectionMode.CONNECTED, restored.mode)
        assertEquals(42L, restored.accountId)
        assertFalse(restored.isLoading)
    }

    @Test
    fun orphanedPkceVerifierIsClearedOnlyWhenNoAuthorizationIsPending() {
        assertTrue(shouldClearOrphanedSimklCodeVerifier(SimklStoredAuthState()))
        assertFalse(
            shouldClearOrphanedSimklCodeVerifier(
                SimklStoredAuthState(pendingAuthorizationState = "valid-pkce-state"),
            ),
        )
        assertFalse(
            shouldClearOrphanedSimklCodeVerifier(
                SimklStoredAuthState(pendingAuthorizationState = buildPendingPinState("ABCDE")),
            ),
        )
    }
}
