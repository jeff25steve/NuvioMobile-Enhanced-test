package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRefreshErrorClassificationTest {
    @Test
    fun recognizesOfficialRefreshTokenRevocationCodes() {
        listOf(
            "refresh_token_not_found",
            "refresh_token_already_used",
            "session_not_found",
            "session_expired",
            "user_banned",
        ).forEach { errorCode ->
            assertTrue(
                isInvalidRefreshResponse(
                    statusCode = 400,
                    errorCode = errorCode,
                    message = "Auth request failed",
                ),
                errorCode,
            )
        }
    }

    @Test
    fun doesNotTreatUnrelatedAuthFailuresAsRevokedRefreshTokens() {
        listOf("invalid_credentials", "over_request_rate_limit", "provider_disabled").forEach { errorCode ->
            assertFalse(
                isInvalidRefreshResponse(
                    statusCode = 400,
                    errorCode = errorCode,
                    message = "Auth request failed",
                ),
                errorCode,
            )
        }
    }

    @Test
    fun keepsLegacyMessageFallbackForUnknownRefreshErrors() {
        assertTrue(
            isInvalidRefreshResponse(
                statusCode = 400,
                errorCode = "new_server_error",
                message = "Invalid Refresh Token: Refresh Token Not Found",
            ),
        )
    }

    @Test
    fun rejectsNonClientFailureStatusesEvenWhenMessageMentionsRefreshToken() {
        assertFalse(
            isInvalidRefreshResponse(
                statusCode = 503,
                errorCode = "refresh_token_not_found",
                message = "Invalid Refresh Token",
            ),
        )
    }
}
