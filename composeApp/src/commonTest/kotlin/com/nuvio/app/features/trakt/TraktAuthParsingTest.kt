package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TraktAuthParsingTest {
    @Test
    fun `PKCE material uses a compliant verifier and S256 challenge`() {
        val material = createTraktPkceMaterial(
            verifierEntropy = ByteArray(32) { it.toByte() },
            stateEntropy = ByteArray(32) { (it + 32).toByte() },
        )

        assertEquals(43, material.verifier.length)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.verifier)
        assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", material.challenge)
        assertTrue(material.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertFalse('=' in material.verifier)
        assertEquals(43, material.challenge.length)
        assertTrue(material.state.length >= 22)
    }

    @Test
    fun `authorization URL uses current auth host and S256 challenge`() {
        val url = buildTraktAuthorizationUrl(
            clientId = "client id",
            redirectUri = "nuvio://auth/trakt",
            material = TraktPkceMaterial("verifier", "challenge-value", "state-value"),
        )

        assertTrue(url.startsWith("https://auth.trakt.tv/oauth/authorize?"))
        assertTrue("response_type=code" in url)
        assertTrue("code_challenge=challenge-value" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=state-value" in url)
        assertFalse("client_secret" in url)
    }

    @Test
    fun `callback parser requires exactly one code and state`() {
        assertEquals(
            TraktAuthCallback.AuthorizationCode("auth-code", "expected"),
            parseTraktAuthCallback(
                "nuvio://auth/trakt?code=auth-code&state=expected",
                "nuvio://auth/trakt",
            ),
        )
        assertIs<TraktAuthCallback.NotTrakt>(
            parseTraktAuthCallback("nuvio://auth/simkl?code=auth-code&state=expected", "nuvio://auth/trakt"),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback("nuvio://auth/trakt?code=auth-code", "nuvio://auth/trakt"),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback("nuvio://auth/trakt?state=expected", "nuvio://auth/trakt"),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback(
                "nuvio://auth/trakt?code=one&code=two&state=expected",
                "nuvio://auth/trakt",
            ),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback(
                "nuvio://auth/trakt?code=auth-code&state=expected&state=other",
                "nuvio://auth/trakt",
            ),
        )
    }

    @Test
    fun `authorization denial also requires one state value`() {
        assertEquals(
            TraktAuthCallback.AuthorizationDenied("User denied", "expected"),
            parseTraktAuthCallback(
                "nuvio://auth/trakt?error=access_denied&error_description=User+denied&state=expected",
                "nuvio://auth/trakt",
            ),
        )
        assertIs<TraktAuthCallback.Invalid>(
            parseTraktAuthCallback("nuvio://auth/trakt?error=access_denied", "nuvio://auth/trakt"),
        )
    }

    @Test
    fun `state validation requires a pending state and exact match`() {
        assertTrue(traktAuthorizationStateMatches("same-state", "same-state"))
        assertFalse(traktAuthorizationStateMatches("same-state", "different-state"))
        assertFalse(traktAuthorizationStateMatches("same-state", "same-state-extra"))
        assertFalse(traktAuthorizationStateMatches(null, "callback-state"))
        assertFalse(traktAuthorizationStateMatches("", "callback-state"))
        assertFalse(traktAuthorizationStateMatches("expected", ""))
    }

    @Test
    fun `pending browser authorization expires after five minutes`() {
        assertFalse(isTraktAuthorizationExpired(1_000L, 301_000L))
        assertTrue(isTraktAuthorizationExpired(1_000L, 301_001L))
        assertTrue(isTraktAuthorizationExpired(null, 1_000L))
        assertTrue(isTraktAuthorizationExpired(2_000L, 1_000L))
    }
}
