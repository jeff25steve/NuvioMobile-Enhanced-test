package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerDiscoveryPolicyTest {
    private val discoveryUrl = "https://self-host.example.com/base/.well-known/nuvio"

    @Test
    fun acceptsBackendOnDiscoveryOriginAndKeepsReverseProxyPath() {
        val configuration = ServerDiscoveryPolicy.parse(
            discoveryUrl = discoveryUrl,
            document = discoveryDocument("https://self-host.example.com/base"),
        )

        assertEquals("https://self-host.example.com/base", configuration.backendUrl)
        assertEquals("public-client-key", configuration.publishableKey)
    }

    @Test
    fun acceptsExplicitDefaultPortAsSameOrigin() {
        val configuration = ServerDiscoveryPolicy.parse(
            discoveryUrl = "https://self-host.example.com:443/.well-known/nuvio",
            document = discoveryDocument("https://self-host.example.com"),
        )

        assertEquals("https://self-host.example.com", configuration.backendUrl)
    }

    @Test
    fun rejectsAdvertisedBackendOnDifferentHost() {
        assertOriginMismatch(discoveryDocument("https://other.example.com"))
    }

    @Test
    fun rejectsAdvertisedBackendOnDifferentProtocol() {
        assertOriginMismatch(discoveryDocument("http://self-host.example.com"))
    }

    @Test
    fun rejectsAdvertisedBackendOnDifferentPort() {
        assertOriginMismatch(discoveryDocument("https://self-host.example.com:8443"))
    }

    @Test
    fun originComparisonUsesTheEffectiveDefaultPort() {
        assertTrue(
            ServerDiscoveryPolicy.hasSameOrigin(
                "https://self-host.example.com/.well-known/nuvio",
                "https://self-host.example.com:443/.well-known/nuvio",
            ),
        )
        assertFalse(
            ServerDiscoveryPolicy.hasSameOrigin(
                "https://self-host.example.com/.well-known/nuvio",
                "https://self-host.example.com:8443/.well-known/nuvio",
            ),
        )
    }

    private fun assertOriginMismatch(document: String) {
        val error = assertFailsWith<ServerDiscoveryException> {
            ServerDiscoveryPolicy.parse(discoveryUrl, document)
        }
        assertEquals(ServerDiscoveryFailure.BackendOriginMismatch, error.failure)
    }

    private fun discoveryDocument(backendUrl: String): String = """
        {
          "version": 1,
          "service": "nuvio",
          "self_hosted": true,
          "backend_url": "$backendUrl",
          "publishable_key": "public-client-key",
          "capabilities": {
            "email_password_auth": true,
            "tv_login": true,
            "watch_party": false
          }
        }
    """.trimIndent()
}
