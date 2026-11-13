package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvalidRemoteSessionResponseTest {
    @Test
    fun forbiddenPermissionFailureDoesNotInvalidateTheSession() {
        assertFalse(
            isInvalidRemoteSessionResponse(
                statusCode = 403,
                message = "permission denied for table watch_progress",
            ),
        )
    }

    @Test
    fun forbiddenInvalidJwtStillInvalidatesTheSession() {
        assertTrue(
            isInvalidRemoteSessionResponse(
                statusCode = 403,
                message = "invalid jwt: signature verification failed",
            ),
        )
    }

    @Test
    fun unauthorizedResponseStillInvalidatesTheSession() {
        assertTrue(
            isInvalidRemoteSessionResponse(
                statusCode = 401,
                message = "unauthorized",
            ),
        )
    }
}
