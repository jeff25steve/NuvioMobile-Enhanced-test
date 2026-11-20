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

    @Test
    fun `parse ignores malformed optional manifest field types and entries`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.test/manifest.json",
            payload = """
                {
                  "id": "addon",
                  "name": "Addon",
                  "version": "1",
                  "description": {"unexpected": true},
                  "logo": ["not", "a", "url"],
                  "types": {"unexpected": true},
                  "idPrefixes": ["tt"],
                  "resources": [
                    "stream",
                    42,
                    [],
                    {"name": "meta", "types": {}, "idPrefixes": [{"bad": true}, "tt123"]},
                    {"name": {"bad": true}}
                  ],
                  "catalogs": [
                    false,
                    {
                      "type": "movie",
                      "id": "popular",
                      "extra": [
                        null,
                        "bad",
                        {"name": 42},
                        {"name": "genre", "options": [1, "action"], "isRequired": "true", "optionsLimit": "2"}
                      ]
                    }
                  ],
                  "behaviorHints": []
                }
            """.trimIndent(),
        )

        assertEquals("", manifest.description)
        assertEquals(null, manifest.logoUrl)
        assertEquals(emptyList(), manifest.types)
        assertEquals(
            listOf(
                AddonResource(name = "stream", types = emptyList(), idPrefixes = listOf("tt")),
                AddonResource(name = "meta", types = emptyList(), idPrefixes = listOf("tt123")),
            ),
            manifest.resources,
        )
        assertEquals(1, manifest.catalogs.size)
        assertEquals(
            listOf(AddonExtraProperty(name = "genre", isRequired = true, options = listOf("action"), optionsLimit = 2)),
            manifest.catalogs.single().extra,
        )
        assertEquals(false, manifest.behaviorHints.configurable)
    }

}
