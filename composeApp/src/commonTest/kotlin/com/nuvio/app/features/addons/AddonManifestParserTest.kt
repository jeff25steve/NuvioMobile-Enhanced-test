package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonManifestParserTest {

    @Test
    fun `parse preserves resource order and fallback metadata while dropping blank primitive names`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.test/manifest.json",
            payload = """
                {
                  "id": "test-addon",
                  "name": "Test Addon",
                  "version": "1.0.0",
                  "types": ["movie", "series"],
                  "idPrefixes": ["tt"],
                  "resources": [
                    "stream",
                    {
                      "name": "meta",
                      "types": ["movie"],
                      "idPrefixes": ["tt123"]
                    },
                    "   "
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                AddonResource(
                    name = "stream",
                    types = listOf("movie", "series"),
                    idPrefixes = listOf("tt"),
                ),
                AddonResource(
                    name = "meta",
                    types = listOf("movie"),
                    idPrefixes = listOf("tt123"),
                ),
            ),
            manifest.resources,
        )
    }

    @Test
    fun `parse resolves root-relative logo from the manifest origin`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://addons.example.test/catalogs/addon/manifest.json?token=secret",
            payload = """{"id":"addon","name":"Addon","version":"1","logo":"/images/logo.png"}""",
        )

        assertEquals("https://addons.example.test/images/logo.png", manifest.logoUrl)
    }

    @Test
    fun `parse resolves scheme-relative logo using manifest scheme`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "http://addons.example.test/catalogs/manifest.json",
            payload = """{"id":"addon","name":"Addon","version":"1","logo":"//cdn.example.test/logo.png"}""",
        )

        assertEquals("http://cdn.example.test/logo.png", manifest.logoUrl)
    }
}
