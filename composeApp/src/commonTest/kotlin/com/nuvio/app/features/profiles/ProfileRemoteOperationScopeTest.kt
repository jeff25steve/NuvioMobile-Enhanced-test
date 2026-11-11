package com.nuvio.app.features.profiles

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileRemoteOperationScopeTest {
    private val official = ServerConfiguration(
        backendUrl = "https://api.example.com",
        publishableKey = "public-key",
        capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
        isCustom = false,
    )
    private val custom = official.copy(
        backendUrl = "https://other.example.com",
        isCustom = true,
    )
    private val scope = ProfileRemoteOperationScope(
        generation = 7L,
        userId = "account-one",
        isAnonymous = false,
        serverConfiguration = official,
    )

    @Test
    fun currentAccountAndBackendMayApplyRemoteProfileResponse() {
        assertTrue(isProfileRemoteOperationCurrent(scope, 7L, "account-one", false, official))
    }

    @Test
    fun accountSwitchRejectsLateRemoteProfileResponse() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, "account-two", false, official))
    }

    @Test
    fun signOutAndInvalidationRejectLateRemoteProfileResponse() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 8L, null, null, official))
    }

    @Test
    fun newerGenerationRejectsResponseEvenWhenAccountIsUnchanged() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 8L, "account-one", false, official))
    }

    @Test
    fun backendSwitchRejectsLateRemoteProfileResponseForSameAccount() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, "account-one", false, custom))
    }

    @Test
    fun anonymousAndAuthenticatedSessionsAreNotInterchangeable() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, "account-one", true, official))
    }
}
