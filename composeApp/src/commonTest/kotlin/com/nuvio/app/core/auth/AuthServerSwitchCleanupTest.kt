package com.nuvio.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthServerSwitchCleanupTest {
    @Test
    fun clearsBothCredentialStoresBeforeWipingLocalAccountData() = runTest {
        val steps = mutableListOf<String>()

        val result = runServerSwitchCleanup(
            clearAnonymousUserId = { steps += "anonymous" },
            clearRemoteSession = { steps += "remote-session" },
            clearLocalAccountData = { steps += "local-account-data" },
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("anonymous", "remote-session", "local-account-data"), steps)
    }

    @Test
    fun doesNotWipeLocalDataWhenAnonymousIdentityClearFails() = runTest {
        val anonymousFailure = IllegalStateException("Anonymous identity could not be cleared")
        val steps = mutableListOf<String>()

        val result = runServerSwitchCleanup(
            clearAnonymousUserId = {
                steps += "anonymous"
                throw anonymousFailure
            },
            clearRemoteSession = { steps += "remote-session" },
            clearLocalAccountData = { steps += "local-account-data" },
        )

        assertSame(anonymousFailure, result.exceptionOrNull())
        assertEquals(listOf("anonymous", "remote-session"), steps)
    }

    @Test
    fun doesNotWipeLocalDataWhenRemoteSessionClearFails() = runTest {
        val remoteFailure = IllegalStateException("Supabase session could not be cleared")
        val steps = mutableListOf<String>()

        val result = runServerSwitchCleanup(
            clearAnonymousUserId = { steps += "anonymous" },
            clearRemoteSession = {
                steps += "remote-session"
                throw remoteFailure
            },
            clearLocalAccountData = { steps += "local-account-data" },
        )

        assertSame(remoteFailure, result.exceptionOrNull())
        assertEquals(listOf("anonymous", "remote-session"), steps)
    }

    @Test
    fun reportsLocalCleanupFailureSoTheBackendSwitchCanBeAborted() = runTest {
        val localFailure = IllegalStateException("A download could not be removed")

        val result = runServerSwitchCleanup(
            clearAnonymousUserId = {},
            clearRemoteSession = {},
            clearLocalAccountData = { throw localFailure },
        )

        assertSame(localFailure, result.exceptionOrNull())
    }

    @Test
    fun propagatesCancellationAndNeverStartsLocalCleanup() = runTest {
        val steps = mutableListOf<String>()
        val cancellation = CancellationException("Switch cancelled")
        var thrown: CancellationException? = null

        try {
            runServerSwitchCleanup(
                clearAnonymousUserId = { steps += "anonymous" },
                clearRemoteSession = {
                    steps += "remote-session"
                    throw cancellation
                },
                clearLocalAccountData = { steps += "local-account-data" },
            )
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("anonymous", "remote-session"), steps)
    }
}
