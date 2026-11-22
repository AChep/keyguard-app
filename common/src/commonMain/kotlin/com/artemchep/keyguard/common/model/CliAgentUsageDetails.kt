package com.artemchep.keyguard.common.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder

/**
 * What the request log shows of a request that only its client knows,
 * stored as JSON. A new field or client needs no migration: a value this
 * build can not read is `null`.
 */
@Serializable
sealed interface CliAgentUsageDetails {
    /** A request of a browser extension. */
    @Serializable
    @SerialName("browser")
    data class Browser(
        /** The origin of the extension that made the request. */
        val extensionOrigin: String,
        /** The origin of the page that the request is for. */
        val pageOrigin: String? = null,
    ) : CliAgentUsageDetails
}

/** Reads the details from a column, or `null` if this build can not. */
internal fun Json.decodeCliAgentUsageDetailsOrNull(value: String): CliAgentUsageDetails? = orNull {
    decodeFromString(CliAgentUsageDetails.serializer(), value)
}

/**
 * Reads the details of a JSON document as `null` if this build can not,
 * so that they never fail the rest of the document.
 */
internal object CliAgentUsageDetailsOrNullSerializer : KSerializer<CliAgentUsageDetails?> {
    private val serializer = CliAgentUsageDetails.serializer().nullable

    override val descriptor = serializer.descriptor

    override fun serialize(encoder: Encoder, value: CliAgentUsageDetails?) =
        encoder.encodeSerializableValue(serializer, value)

    override fun deserialize(decoder: Decoder): CliAgentUsageDetails? {
        val input = decoder as JsonDecoder
        val element = input.decodeJsonElement()
        return orNull {
            input.json.decodeFromJsonElement(CliAgentUsageDetails.serializer(), element)
        }
    }
}

/** A malformed value, or one of a client that this build does not know. */
private inline fun orNull(block: () -> CliAgentUsageDetails): CliAgentUsageDetails? = try {
    block()
} catch (_: SerializationException) {
    null
}
