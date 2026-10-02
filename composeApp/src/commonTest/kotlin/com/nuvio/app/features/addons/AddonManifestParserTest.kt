package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonManifestParserTest {

    @Test
    fun `root relative logo resolves against manifest origin`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.com/addon/manifest.json",
            payload = """{
                "id": "test",
                "name": "Test",
                "version": "1.0.0",
                "logo": "/logo.png"
            }""",
        )

        assertEquals("https://example.com/logo.png", manifest.logoUrl)
    }
}
