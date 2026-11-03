package com.artemchep.keyguard.feature.environments

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The environment document: the one representation that is synced, stored and exported.
 *
 * Compatibility rules, which every installed version relies on:
 * - `version` is the only key a reader must understand. A document of another version is unsupported.
 * - Every other unknown key is ignored. A document that has one can be run, but not edited,
 *   and is written back exactly as it was read.
 * - A change that alters a delivered value must use a new source `kind` or a new `version`,
 *   never a new key on an existing source.
 * - Variable names and values are checked by the editor and before delivery, not here, so a later
 *   rule never hides a document from older versions.
 */
object EnvironmentFormat {
    /** The largest document Keyguard writes. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    /** The largest document Keyguard reads; larger than [MAX_PAYLOAD_BYTES], so the write limit can grow. */
    const val MAX_READ_BYTES = 1024 * 1024

    private const val VERSION = 1

    private val writer = Json { encodeDefaults = true }

    private val reader = Json(writer) { ignoreUnknownKeys = true }

    @Serializable
    private data class Document(
        val version: Int,
        val variables: List<EnvironmentVariable>,
    )

    fun encode(definition: EnvironmentDefinition): String {
        val value = writer.encodeToString(Document(version = VERSION, variables = definition.variables))
        require(value.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "Environment is too large" }
        return value
    }

    /** Returns `null` if the [document] is not a document this version can read. */
    fun decode(document: String): EnvironmentPayload.Decoded? = try {
        require(document.encodeToByteArray().size <= MAX_READ_BYTES)
        val element = reader.parseToJsonElement(document)
        val value = reader.decodeFromJsonElement(Document.serializer(), element)
        require(value.version == VERSION)
        EnvironmentPayload.Decoded(
            definition = EnvironmentDefinition(value.variables),
            document = document,
            lossless = writer.encodeToJsonElement(Document.serializer(), value) == element,
        )
    } catch (_: IllegalArgumentException) {
        // Includes SerializationException.
        null
    }
}
