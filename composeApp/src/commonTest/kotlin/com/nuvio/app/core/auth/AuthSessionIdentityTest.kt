package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionIdentityTest {
    private val official = ServerConfiguration(
        backendUrl = "https://api.example.com",
        publishableKey = "public-key",
        capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
        isCustom = false,
    )
    private val custom = official.copy(backendUrl = "https://other.example.com", isCustom = true)
    private val identity = AuthSessionIdentity(
        sessionGeneration = 4L,
        userId = "account-one",
        isAnonymous = false,
        serverConfiguration = official,
    )

    @Test
    fun currentIdentityRequiresMatchingGenerationAccountBackendAndSdkUser() {
        assertTrue(isAuthSessionIdentityCurrent(identity, 4L, "account-one", false, official, "account-one"))
    }

    @Test
    fun newerSessionGenerationRejectsAnOldRequestForTheSameAccount() {
        assertFalse(isAuthSessionIdentityCurrent(identity, 5L, "account-one", false, official, "account-one"))
    }

    @Test
    fun replacementAccountCannotBeSignedOutByAnOlderRequest() {
        assertFalse(isAuthSessionIdentityCurrent(identity, 4L, "account-two", false, official, "account-two"))
    }

    @Test
    fun replacementSdkSessionIsRejectedEvenWhenCachedAppStateIsStale() {
        assertFalse(isAuthSessionIdentityCurrent(identity, 4L, "account-one", false, official, "account-two"))
    }

    @Test
    fun clearedSdkSessionCannotBeMistakenForTheOriginalAuthenticatedSession() {
        assertFalse(isAuthSessionIdentityCurrent(identity, 4L, "account-one", false, official, null))
    }

    @Test
    fun changedBackendRejectsAnOldRequestEvenForTheSameAccount() {
        assertFalse(isAuthSessionIdentityCurrent(identity, 4L, "account-one", false, custom, "account-one"))
    }

    @Test
    fun anonymousIdentityRequiresNoSupabaseUserSession() {
        val anonymous = identity.copy(isAnonymous = true)
        assertTrue(isAuthSessionIdentityCurrent(anonymous, 4L, "account-one", true, official, null))
        assertFalse(isAuthSessionIdentityCurrent(anonymous, 4L, "account-one", true, official, "account-two"))
    }
}
