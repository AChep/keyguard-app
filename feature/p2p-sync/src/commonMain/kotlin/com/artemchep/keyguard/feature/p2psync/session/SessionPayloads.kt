package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * The HELLO payload: protocol versions, capabilities, identity and state hashes.
 *
 * @property caps the capabilities of the sender. There are none yet: a later build lists
 *   what it adds, and an older one ignores what it does not know.
 */
@Serializable
internal data class HelloPayload(
    val v: Int,
    val min: Int,
    val caps: List<String> = emptyList(),
    val device: String,
    val group: String,
    val roster: String,
    val envelope: String? = null,
)

/** The payload of ENVELOPE_ACK and ENVELOPE_TOO_LARGE: the content hash of one envelope. */
@Serializable
internal data class HashPayload(
    val h: String,
)

/** The BYE payload. */
@Serializable
internal data class ByePayload(
    val reason: String,
)

/** Encodes a JSON payload. */
internal fun <T> KSerializer<T>.encodePayload(value: T): ByteArray =
    P2pJson.encodeToString(this, value).encodeToByteArray()

/** Decodes a JSON payload, or returns `null` if it is invalid or past [EnvelopeCodec.limits]. */
internal fun <T> KSerializer<T>.decodePayloadOrNull(bytes: ByteArray): T? = try {
    // A decoder allocates as it reads: check the limits before it runs.
    if (EnvelopeCodec.limits.admits(bytes)) P2pJson.decodeFromString(this, bytes.decodeToString()) else null
} catch (expected: IllegalArgumentException) {
    null
}
