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
        val cleanup = launch {
            runInvalidSessionCleanup(
                clearAnonymousUserId = {},
                clearRemoteSession = { throw remoteFailure },
                clearLocalAccountData = { throw localFailure },
            )
        }

        cleanup.cancelAndJoin()

        assertTrue(cleanup.isCancelled)
        val cancellation = cleanup.getCancellationException()
        assertTrue(cancellation.suppressedExceptions.contains(remoteFailure))
        assertTrue(cancellation.suppressedExceptions.contains(localFailure))
    }
}
