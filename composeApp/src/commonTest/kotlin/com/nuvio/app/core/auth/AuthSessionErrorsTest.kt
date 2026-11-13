package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertTrue

class AuthSessionErrorsTest {
    @Test
    fun collectsInvalidSessionEvidenceFromNestedCauses() {
        val nestedCause = IllegalStateException("session_not_found")
        val wrappedError = IllegalStateException("Remote user lookup failed", nestedCause)

        val messages = wrappedError.authMessages()

        assertTrue("remote user lookup failed" in messages)
        assertTrue("session_not_found" in messages)
    }
}
