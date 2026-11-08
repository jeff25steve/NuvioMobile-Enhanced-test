package com.nuvio.app.features.membership

import com.nuvio.app.core.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberAccessCacheScopeTest {

    @Test
    fun `cached membership access is not hydrated before authentication resolves`() {
        assertFalse(canHydrateMemberAccess(AuthState.Loading, "account-a"))
        assertFalse(canHydrateMemberAccess(AuthState.Unauthenticated, "account-a"))
    }

    @Test
    fun `anonymous sessions do not hydrate a previous account entitlement`() {
        val anonymous = AuthState.Authenticated(
            userId = "account-a",
            email = null,
            isAnonymous = true,
        )

        assertFalse(canHydrateMemberAccess(anonymous, "account-a"))
    }

    @Test
    fun `only the authenticated owner can hydrate cached membership access`() {
        val authenticated = AuthState.Authenticated(
            userId = "account-a",
            email = null,
            isAnonymous = false,
        )

        assertTrue(canHydrateMemberAccess(authenticated, "account-a"))
        assertFalse(canHydrateMemberAccess(authenticated, "account-b"))
    }

    @Test
    fun `an older membership request cannot publish after cleanup advances its generation`() {
        val authenticated = AuthState.Authenticated(
            userId = "account-a",
            email = null,
            isAnonymous = false,
        )

        assertTrue(canPublishMemberAccess(authenticated, "account-a", 4L, 4L))
        assertFalse(canPublishMemberAccess(authenticated, "account-a", 4L, 5L))
        assertFalse(canPublishMemberAccess(AuthState.Unauthenticated, "account-a", 4L, 4L))
    }
}
