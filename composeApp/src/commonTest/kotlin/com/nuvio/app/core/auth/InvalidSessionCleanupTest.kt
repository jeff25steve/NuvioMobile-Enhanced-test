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
    fun attemptsLocalCleanupEvenWhenRemoteSessionClearFails() = runTest {
        var localCleanupStarted = false

        val result = runInvalidSessionCleanup(
            clearAnonymousUserId = {},
            clearRemoteSession = { error("Remote session clear failed") },
            clearLocalAccountData = { localCleanupStarted = true },
        )

        assertTrue(localCleanupStarted)
        assertTrue(result.isFailure)
    }
}
