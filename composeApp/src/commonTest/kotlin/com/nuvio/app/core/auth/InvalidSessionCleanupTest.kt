package com.nuvio.app.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvalidSessionCleanupTest {
    @Test
    fun cancellationDuringRemoteClearStillCompletesLocalCleanup() = runTest {
        val remoteClearStarted = CompletableDeferred<Unit>()
        val finishRemoteClear = CompletableDeferred<Unit>()
        val localCleanupFinished = CompletableDeferred<Unit>()

        val cleanup = launch {
            runInvalidSessionCleanup(
                clearAnonymousUserId = {},
                clearRemoteSession = {
                    remoteClearStarted.complete(Unit)
                    finishRemoteClear.await()
                },
                clearLocalAccountData = { localCleanupFinished.complete(Unit) },
            )
        }

        remoteClearStarted.await()
        cleanup.cancel()
        finishRemoteClear.complete(Unit)
        cleanup.cancelAndJoin()

        assertTrue(localCleanupFinished.isCompleted)
        assertTrue(cleanup.isCancelled)
    }

    @Test
    fun doesNotWipeLocalDataWhenSessionIdentityChangesDuringCleanup() = runTest {
        var sessionIsCurrent = true
        var localCleanupStarted = false

        runInvalidSessionCleanup(
            clearAnonymousUserId = {},
            clearRemoteSession = { sessionIsCurrent = false },
            clearLocalAccountData = { localCleanupStarted = true },
            shouldContinue = { sessionIsCurrent },
        )

        assertFalse(localCleanupStarted)
    }

    @Test
    fun preservesEveryCleanupFailureWhenReturningFailureResult() = runTest {
        val anonymousFailure = IllegalStateException("Anonymous clear failed")
        val remoteFailure = IllegalStateException("Remote clear failed")
        val localFailure = IllegalStateException("Local cleanup failed")

        val result = runInvalidSessionCleanup(
            clearAnonymousUserId = { throw anonymousFailure },
            clearRemoteSession = { throw remoteFailure },
            clearLocalAccountData = { throw localFailure },
        )

        assertTrue(result.exceptionOrNull() === anonymousFailure)
        assertTrue(anonymousFailure.suppressedExceptions.contains(remoteFailure))
        assertTrue(anonymousFailure.suppressedExceptions.contains(localFailure))
    }

    @Test
    fun preservesCleanupFailuresWhenCallerCancellationWins() = runTest {
        val remoteFailure = IllegalStateException("Remote clear failed")
        val localFailure = IllegalStateException("Local cleanup failed")
        val remoteClearStarted = CompletableDeferred<Unit>()
        val finishRemoteClear = CompletableDeferred<Unit>()
        var completionFailure: Throwable? = null
        val cleanup = launch {
            runInvalidSessionCleanup(
                clearAnonymousUserId = {},
                clearRemoteSession = {
                    remoteClearStarted.complete(Unit)
                    finishRemoteClear.await()
                    throw remoteFailure
                },
                clearLocalAccountData = { throw localFailure },
            )
        }
        cleanup.invokeOnCompletion { completionFailure = it }

        remoteClearStarted.await()
        cleanup.cancel()
        finishRemoteClear.complete(Unit)
        cleanup.cancelAndJoin()

        assertTrue(cleanup.isCancelled)
        assertTrue(completionFailure is kotlinx.coroutines.CancellationException)
        assertTrue(completionFailure!!.suppressedExceptions.contains(remoteFailure))
        assertTrue(completionFailure!!.suppressedExceptions.contains(localFailure))
    }
}
