package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonManifestParserTest {

    @Test
    fun `parse preserves resource order and fallback metadata while dropping blank names`() {
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
                    {
                      "name": "",
                      "types": ["series"]
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
}
