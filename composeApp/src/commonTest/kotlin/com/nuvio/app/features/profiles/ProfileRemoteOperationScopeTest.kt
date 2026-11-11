package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthSessionIdentity
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
    private val custom = official.copy(backendUrl = "https://other.example.com", isCustom = true)
    private val identity = AuthSessionIdentity(4L, "account-one", false, official)
    private val scope = ProfileRemoteOperationScope(7L, identity)

    @Test
    fun currentAccountAndBackendMayApplyRemoteProfileResponse() {
        assertTrue(isProfileRemoteOperationCurrent(scope, 7L, identity))
    }

    @Test
    fun profileGenerationRejectsLateRemoteProfileResponse() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 8L, identity))
    }

    @Test
    fun authSessionGenerationRejectsLateResponseEvenForTheSameAccount() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, identity.copy(sessionGeneration = 5L)))
    }

    @Test
    fun accountSwitchRejectsLateRemoteProfileResponse() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, identity.copy(userId = "account-two")))
    }

    @Test
    fun backendSwitchRejectsLateRemoteProfileResponseForSameAccount() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, identity.copy(serverConfiguration = custom)))
    }

    @Test
    fun anonymousAndAuthenticatedSessionsAreNotInterchangeable() {
        assertFalse(isProfileRemoteOperationCurrent(scope, 7L, identity.copy(isAnonymous = true)))
    }
}
