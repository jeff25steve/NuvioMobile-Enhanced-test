package com.nuvio.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun preservesBothCredentialClearFailuresWhenAbortingSwitch() = runTest {
        val anonymousFailure = IllegalStateException("Anonymous clear failed")
        val remoteFailure = IllegalStateException("Remote clear failed")

        val result = runServerSwitchCleanup(
            clearAnonymousUserId = { throw anonymousFailure },
            clearRemoteSession = { throw remoteFailure },
            clearLocalAccountData = { error("Local cleanup must not start") },
        )

        assertSame(anonymousFailure, result.exceptionOrNull())
        assertTrue(anonymousFailure.suppressedExceptions.contains(remoteFailure))
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
    fun cancellationDuringAnonymousClearStillFinishesRemoteCredentialClear() = runTest {
        val anonymousClearStarted = CompletableDeferred<Unit>()
        val finishAnonymousClear = CompletableDeferred<Unit>()
        val remoteClearFinished = CompletableDeferred<Unit>()
        var localCleanupStarted = false

        val cleanup = launch {
            runServerSwitchCleanup(
                clearAnonymousUserId = {
                    anonymousClearStarted.complete(Unit)
                    finishAnonymousClear.await()
                },
                clearRemoteSession = {
                    remoteClearFinished.complete(Unit)
                },
                clearLocalAccountData = { localCleanupStarted = true },
            )
        }

        anonymousClearStarted.await()
        cleanup.cancel()
        finishAnonymousClear.complete(Unit)
        cleanup.cancelAndJoin()

        assertTrue(remoteClearFinished.isCompleted)
        assertFalse(localCleanupStarted)
    }

    @Test
    fun cancellationAfterCredentialCleanupPreservesFailuresAndSkipsLocalCleanup() = runTest {
        val remoteClearStarted = CompletableDeferred<Unit>()
        val finishRemoteClear = CompletableDeferred<Unit>()
        val remoteFailure = IllegalStateException("Remote clear failed")
        val localFailure = IllegalStateException("Anonymous clear failed")
        var completionFailure: Throwable? = null
        var localCleanupStarted = false
        val cleanup = launch {
            runServerSwitchCleanup(
                clearAnonymousUserId = { throw localFailure },
                clearRemoteSession = {
                    remoteClearStarted.complete(Unit)
                    finishRemoteClear.await()
                    throw remoteFailure
                },
                clearLocalAccountData = { localCleanupStarted = true },
            )
        }
        cleanup.invokeOnCompletion { completionFailure = it }

        remoteClearStarted.await()
        cleanup.cancel()
        finishRemoteClear.complete(Unit)
        cleanup.cancelAndJoin()

        assertFalse(localCleanupStarted)
        assertTrue(completionFailure is CancellationException)
        assertTrue(completionFailure!!.suppressedExceptions.contains(localFailure))
        assertTrue(completionFailure!!.suppressedExceptions.contains(remoteFailure))
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
