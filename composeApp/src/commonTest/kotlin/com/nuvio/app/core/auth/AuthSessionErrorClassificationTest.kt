package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionErrorClassificationTest {
    @Test
    fun doesNotTreatForbiddenRowLevelSecurityAsAnInvalidSession() {
        assertFalse(
            isInvalidRemoteSessionResponse(
                statusCode = 403,
                errorCode = "42501",
                message = "permission denied for table profiles",
            ),
        )
    }

    @Test
    fun doesNotTreatInvalidApiKeyAsAnInvalidUserSession() {
        assertFalse(
            isInvalidRemoteSessionResponse(
                statusCode = 401,
                errorCode = "invalid_api_key",
                message = "Invalid API key",
            ),
        )
    }

    @Test
    fun recognizesExplicitInvalidSessionResponsesEvenWhenForbidden() {
        assertTrue(
            isInvalidRemoteSessionResponse(
                statusCode = 403,
                errorCode = "session_not_found",
                message = "Session not found",
            ),
        )
    }

    @Test
    fun recognizesExpiredOrMalformedJwtResponses() {
        assertTrue(
            isInvalidRemoteSessionResponse(
                statusCode = 401,
                errorCode = "bad_jwt",
                message = "Invalid JWT: token is malformed",
            ),
        )
    }
}
