package com.nuvio.app.features.addons

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.addons_manifest_missing_field
import org.jetbrains.compose.resources.getString

internal object AddonManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun parse(
        manifestUrl: String,
        payload: String,
    ): AddonManifest {
        val root = json.parseToJsonElement(payload).jsonObject
        val defaultTypes = root.stringList("types")
        val defaultPrefixes = root.stringList("idPrefixes")

        return AddonManifest(
            id = root.requiredString("id"),
            name = root.requiredString("name"),
            description = root.optionalString("description").orEmpty(),
            version = root.requiredString("version"),
            logoUrl = root.optionalString("logo")?.resolveAgainstManifest(manifestUrl),
            resources = root.resources(defaultTypes, defaultPrefixes),
            types = defaultTypes,
            idPrefixes = defaultPrefixes,
            catalogs = root.catalogs(),
            behaviorHints = root.behaviorHints(),
            transportUrl = manifestUrl,
        )
    }

    private fun JsonObject.resources(
        defaultTypes: List<String>,
        defaultPrefixes: List<String>,
    ): List<AddonResource> = buildList {
        for (resource in array("resources")) {
            when (resource) {
                is JsonPrimitive -> resource.takeIf { it.isString }?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        add(AddonResource(name = name, types = defaultTypes, idPrefixes = defaultPrefixes))
                    }

                is JsonObject -> {
                    val name = resource.optionalString("name")?.takeIf(String::isNotBlank) ?: continue
                    add(
                        AddonResource(
                            name = name,
                            types = resource.stringList("types").ifEmpty { defaultTypes },
                            idPrefixes = resource.stringList("idPrefixes").ifEmpty { defaultPrefixes },
                        ),
                    )
                }

                else -> continue
            }
        }
    }

    private fun JsonObject.catalogs(): List<AddonCatalog> = buildList {
        for (catalogElement in array("catalogs")) {
            val catalog = catalogElement as? JsonObject ?: continue
            val type = catalog.optionalString("type")?.takeIf(String::isNotBlank) ?: continue
            val id = catalog.optionalString("id")?.takeIf(String::isNotBlank) ?: continue
            add(
                AddonCatalog(
                    type = type,
                    id = id,
                    name = catalog.optionalString("name").orEmpty().ifBlank { id },
                    extra = catalog.array("extra").mapNotNull { extraElement ->
                        val extra = extraElement as? JsonObject ?: return@mapNotNull null
                        val name = extra.optionalString("name")?.takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        AddonExtraProperty(
                            name = name,
                            isRequired = extra.boolean("isRequired"),
                            options = extra.stringList("options"),
                            optionsLimit = extra.int("optionsLimit"),
                        )
                    },
                ),
            )
        }
    }

    private fun JsonObject.behaviorHints(): AddonBehaviorHints {
        val hints = this["behaviorHints"] as? JsonObject ?: return AddonBehaviorHints()
        return AddonBehaviorHints(
            configurable = hints.boolean("configurable"),
            configurationRequired = hints.boolean("configurationRequired"),
            adult = hints.boolean("adult"),
            p2p = hints.boolean("p2p"),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                runBlocking { getString(Res.string.addons_manifest_missing_field, name) },
            )

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.stringList(name: String): List<String> =
        array(name).mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun JsonObject.array(name: String): JsonArray =
        (this[name] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

private fun String.resolveAgainstManifest(manifestUrl: String): String {
    if (
        startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) ||
        startsWith("data:", ignoreCase = true)
    ) {
        return this
    }

    val baseUrl = manifestUrl.substringBefore('#').substringBefore('?')
    val schemeSeparator = baseUrl.indexOf("://")
    val scheme = baseUrl.takeIf { schemeSeparator > 0 }?.substring(0, schemeSeparator)
    val authorityStart = if (schemeSeparator > 0) schemeSeparator + 3 else -1
    val authorityEnd = if (authorityStart >= 0) {
        baseUrl.indexOf('/', authorityStart).takeIf { it >= 0 } ?: baseUrl.length
    } else {
        -1
    }
    val origin = if (authorityEnd >= 0) baseUrl.substring(0, authorityEnd) else null
    val manifestPath = if (authorityEnd >= 0) baseUrl.substring(authorityEnd) else ""
    val manifestDirectory = if (origin != null) {
        origin + manifestPath.substringBeforeLast('/', "")
    } else {
        baseUrl.substringBeforeLast('/', "")
    }

    return when {
        startsWith("//") -> "${scheme ?: "https"}:$this"
        startsWith("/") -> "${origin ?: manifestDirectory}$this"
        else -> "$manifestDirectory/$this"
    }
}
