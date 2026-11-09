package com.artemchep.keyguard.feature.p2psync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON configuration of every wire and storage format of this module. Unknown keys are
 * ignored so that older builds read newer payloads, defaults are always written so that a
 * re-encoded payload hashes the same, and `null` values are left out.
 */
val P2pJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// A SerializationException is an IllegalArgumentException.

/**
 * Parses [bytes] as a JSON object, or returns `null` if they are not one. Check the limits
 * of the bytes before: the parser recurses into nested arrays, and its tree takes many
 * times the bytes.
 */
internal fun parseJsonObjectOrNull(bytes: ByteArray): JsonObject? = try {
    P2pJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
} catch (expected: IllegalArgumentException) {
    null
}

/** Decodes the [element], or returns `null` if it is not a valid value. */
internal fun <T> KSerializer<T>.decodeOrNull(element: JsonElement): T? = try {
    P2pJson.decodeFromJsonElement(this, element)
} catch (expected: IllegalArgumentException) {
    null
}

/** The string of this element, or `null` if it is no string. */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string field [name], or `null` if there is none or it is no string. */
fun JsonObject.string(name: String): String? = get(name).stringOrNull()
