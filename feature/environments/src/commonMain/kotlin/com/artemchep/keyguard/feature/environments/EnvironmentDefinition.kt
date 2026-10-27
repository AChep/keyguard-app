package com.artemchep.keyguard.feature.environments

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

data class EnvironmentDefinition(
    val variables: List<EnvironmentVariable> = emptyList(),
) {
    val hasUnsupportedSources: Boolean
        get() = variables.any { it.source is EnvironmentValueSource.Unsupported }
}

@Serializable
data class EnvironmentVariable(
    val name: String,
    val source: EnvironmentValueSource,
)

/**
 * Item references carry provider-stable item identity, like cipher links, and take their
 * account context from the environment. Device-local cipher IDs are only suitable for
 * local handles/mounts. Literal text is never parsed as a reference or expression.
 */
@Serializable(with = EnvironmentValueSourceSerializer::class)
sealed interface EnvironmentValueSource {
    data object Unset : EnvironmentValueSource

    data class Literal(val value: String) : EnvironmentValueSource {
        override fun toString(): String = "Literal([redacted])"
    }

    /**
     * The value of a field of another item of the same account, resolved on
     * every read. [cipherId] is the canonical provider id of that item.
     */
    data class Reference(
        val cipherId: String,
        val field: EnvironmentFieldReference,
    ) : EnvironmentValueSource {
        override fun toString(): String = "Reference([redacted])"
    }

    data class Unsupported(val raw: JsonElement) : EnvironmentValueSource {
        override fun toString(): String = "Unsupported([redacted])"
    }
}

/** A field of the referenced item. Custom fields have no ids, so they match by name. */
sealed interface EnvironmentFieldReference {
    data object Password : EnvironmentFieldReference

    data class Custom(val name: String) : EnvironmentFieldReference {
        override fun toString(): String = "Custom([redacted])"
    }
}

/**
 * Unknown kinds are retained verbatim; older clients must never reinterpret them as literals.
 * Unknown keys of a known kind are ignored, see [EnvironmentFormat].
 */
object EnvironmentValueSourceSerializer : KSerializer<EnvironmentValueSource> {
    override val descriptor = buildClassSerialDescriptor("EnvironmentValueSource")

    override fun deserialize(decoder: Decoder): EnvironmentValueSource {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("Environment sources require JSON")
        val raw = json.decodeJsonElement()
        val obj = raw as? JsonObject ?: return EnvironmentValueSource.Unsupported(raw)
        val source = when ((obj["kind"] as? JsonPrimitive)?.contentOrNull) {
            "unset" -> EnvironmentValueSource.Unset
            "literal" -> obj.stringOrNull("value")?.let(EnvironmentValueSource::Literal)
            KIND_CIPHER_FIELD -> deserializeReference(obj)
            else -> null
        }
        return source ?: EnvironmentValueSource.Unsupported(raw)
    }

    /** Strict on the known keys: a malformed reference is kept verbatim, never guessed. */
    private fun deserializeReference(obj: JsonObject): EnvironmentValueSource.Reference? {
        val cipherId = obj.stringOrNull("cipherId")
            ?.takeIf(::isCanonicalUuid)
        val field = when (obj.stringOrNull("field")) {
            FIELD_PASSWORD -> EnvironmentFieldReference.Password

            FIELD_CUSTOM -> obj.stringOrNull("name")
                ?.takeIf { it.isNotBlank() }
                ?.let(EnvironmentFieldReference::Custom)

            else -> null
        }
        if (cipherId == null || field == null) return null
        return EnvironmentValueSource.Reference(cipherId, field)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun serialize(encoder: Encoder, value: EnvironmentValueSource) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("Environment sources require JSON")
        val element = when (value) {
            EnvironmentValueSource.Unset -> buildJsonObject { put("kind", "unset") }
            is EnvironmentValueSource.Literal -> buildJsonObject {
                put("kind", "literal")
                put("value", value.value)
            }
            is EnvironmentValueSource.Reference -> buildJsonObject {
                put("kind", KIND_CIPHER_FIELD)
                put("cipherId", value.cipherId)
                when (val field = value.field) {
                    EnvironmentFieldReference.Password -> put("field", FIELD_PASSWORD)
                    is EnvironmentFieldReference.Custom -> {
                        put("field", FIELD_CUSTOM)
                        put("name", field.name)
                    }
                }
            }
            is EnvironmentValueSource.Unsupported -> value.raw
        }
        json.encodeJsonElement(element)
    }

    private const val KIND_CIPHER_FIELD = "cipherField"
    private const val FIELD_PASSWORD = "password"
    private const val FIELD_CUSTOM = "custom"
}

/** Item ids of references must be canonical, so that equal references are equal strings. */
internal fun isCanonicalUuid(value: String): Boolean = try {
    Uuid.parse(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}

/** The environment of an item, as this version reads it. */
sealed interface EnvironmentPayload {
    /** The `EnvironmentFormat` document, written back as it is unless the user edits it. */
    val document: String

    /**
     * A document this version can read. The [document] is written back as it is,
     * unless the user edits the [definition].
     *
     * @property lossless `true` if encoding the [definition] gives back the [document],
     *   so this version read every key of it.
     */
    data class Decoded(
        val definition: EnvironmentDefinition,
        override val document: String,
        val lossless: Boolean,
    ) : EnvironmentPayload {
        override fun toString(): String = "Decoded([redacted])"
    }

    /** A [document] this version can not read, such as one of an unknown version, kept verbatim. */
    data class Unsupported(
        override val document: String,
    ) : EnvironmentPayload {
        override fun toString(): String = "Unsupported([redacted])"
    }
}

/** The definition, or `null` if it is not decoded or has sources this version does not support. */
fun EnvironmentPayload?.runnableDefinition(): EnvironmentDefinition? =
    (this as? EnvironmentPayload.Decoded)?.definition?.takeUnless { it.hasUnsupportedSources }

/** The definition, or `null` if editing it would lose data this version does not read. */
fun EnvironmentPayload?.editableDefinition(): EnvironmentDefinition? =
    (this as? EnvironmentPayload.Decoded)?.takeIf { it.lossless }.runnableDefinition()

/**
 * The environment for a copy in another account, where references mean nothing, so they
 * become unset. The document is kept as it is if it has no references. Returns `null` if this
 * version can not tell the references apart, or would have to rewrite a document it does not fully read.
 */
fun EnvironmentPayload?.withoutReferences(): EnvironmentPayload.Decoded? {
    val decoded = (this as? EnvironmentPayload.Decoded)
        ?.takeUnless { it.definition.hasUnsupportedSources }
    val variables = decoded?.definition?.variables.orEmpty()
    return when {
        decoded == null || variables.none { it.source is EnvironmentValueSource.Reference } -> decoded
        !decoded.lossless -> null
        else -> {
            val definition = EnvironmentDefinition(
                variables = variables.map { variable ->
                    if (variable.source is EnvironmentValueSource.Reference) {
                        variable.copy(source = EnvironmentValueSource.Unset)
                    } else {
                        variable
                    }
                },
            )
            EnvironmentPayload.Decoded(definition, EnvironmentFormat.encode(definition), lossless = true)
        }
    }
}
