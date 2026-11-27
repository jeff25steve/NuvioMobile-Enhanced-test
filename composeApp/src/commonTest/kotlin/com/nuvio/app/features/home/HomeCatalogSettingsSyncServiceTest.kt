package com.nuvio.app.features.home

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun `catalog title style sync round trips and old records remain readable`() {
        val style = HomeCatalogTitleStyle(
            fontId = "serif",
            fontSizeSp = 28,
            bold = true,
            colorId = "purple",
        )
        val item = SyncCatalogItem(
            addonId = "addon",
            type = "movie",
            catalogId = "popular",
            titleStyle = style,
        )

        val encoded = Json.encodeToString(item)
        assertEquals(style, Json.decodeFromString<SyncCatalogItem>(encoded).titleStyle)

        val legacy = Json.decodeFromString<SyncCatalogItem>(
            """{"addon_id":"addon","type":"movie","catalog_id":"popular"}""",
        )
        assertEquals(null, legacy.titleStyle)
    }

    @Test
    fun `shared settings merge preserves unknown remote fields`() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("show_catalog_type", false)
        }
        val local = buildJsonObject {
            put("show_catalog_type", true)
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.content.toBoolean())
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }
}
