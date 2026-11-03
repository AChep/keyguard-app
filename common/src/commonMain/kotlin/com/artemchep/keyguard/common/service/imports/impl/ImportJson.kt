package com.artemchep.keyguard.common.service.imports.impl

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

internal const val IMPORT_EXTENSION_JSON = "json"

/**
 * The deepest nesting an imported JSON file may have. Real exports nest a
 * handful of levels; the bound exists because Kotlin/Native cannot catch the
 * stack overflow a recursive parser hits on a hostile file.
 */
internal const val IMPORT_JSON_MAX_DEPTH = 64

/**
 * The only JSON parser of the importers. Every way it reads text checks the
 * nesting against [maxDepth] first, so an importer cannot forget the bound —
 * not for a whole file, and not for JSON inside a CSV cell.
 */
internal class ImportJsonParser(
    private val json: Json,
    private val maxDepth: Int = IMPORT_JSON_MAX_DEPTH,
) {
    fun parseToJsonElement(
        text: String,
    ): JsonElement = json.parseToJsonElement(requireImportJsonDepth(text, maxDepth))

    fun <T> decodeFromString(
        deserializer: DeserializationStrategy<T>,
        text: String,
    ): T = json.decodeFromString(deserializer, requireImportJsonDepth(text, maxDepth))

    /**
     * Needs no bound of its own: the [element] came from this parser.
     */
    fun <T> decodeFromJsonElement(
        deserializer: DeserializationStrategy<T>,
        element: JsonElement,
    ): T = json.decodeFromJsonElement(deserializer, element)
}

internal inline fun <reified T> ImportJsonParser.decodeFromString(
    text: String,
): T = decodeFromString(serializer<T>(), text)

internal inline fun <reified T> ImportJsonParser.decodeFromJsonElement(
    element: JsonElement,
): T = decodeFromJsonElement(serializer<T>(), element)

/**
 * Lenient on purpose: exporters add members over time and write `null` where
 * this side expects a default.
 */
internal val importJson = ImportJsonParser(
    json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    },
)

internal class ImportJsonTooDeepException : IllegalArgumentException()

/**
 * Returns [text] if its nesting is within [maxDepth]; brackets inside strings
 * do not count.
 */
private fun requireImportJsonDepth(
    text: String,
    maxDepth: Int,
): String {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                if (depth > maxDepth) {
                    throw ImportJsonTooDeepException()
                }
            }

            '}', ']' -> depth--
        }
    }
    return text
}
