package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat

/**
 * Carries an environment in the custom fields of a Bitwarden secure note as
 * [BitwardenChunkedFields]. A group that does not decode is not an environment:
 * the item keeps its fields as they are, as with GPG keys.
 */
internal object BitwardenEnvironmentFieldCodec {
    private const val BASE_NAME = "keyguard.environment"
    private val PREFIX = BitwardenChunkedFields.prefix(BASE_NAME)

    data class Decoded(
        val environment: BitwardenCipher.Environment,
        val remainingFields: List<BitwardenCipher.Field>,
    )

    fun isTransportField(name: String?): Boolean = name?.startsWith(PREFIX) == true

    /**
     * Only these types may carry environment transport fields. Other
     * types must keep user-created fields with matching names untouched.
     */
    fun appliesTo(type: BitwardenCipher.Type): Boolean =
        type == BitwardenCipher.Type.SecureNote ||
                type == BitwardenCipher.Type.Environment

    fun encode(environment: BitwardenCipher.Environment): List<BitwardenCipher.Field> {
        // The fields are what marks the item as an environment, so an empty one writes
        // an empty document: without fields it would sync back as a secure note.
        val document = environment.document
            ?: EnvironmentFormat.encode(EnvironmentDefinition())
        return BitwardenChunkedFields.encode(BASE_NAME, document, BitwardenCipher.Field.Type.Hidden)
    }

    /** Returns `null` if the [fields] carry no intact environment. */
    fun decode(fields: List<BitwardenCipher.Field>): Decoded? {
        val (transport, remaining) = fields.partition { isTransportField(it.name) }
        return BitwardenChunkedFields.decode(BASE_NAME, transport, BitwardenCipher.Field.Type.Hidden)
            ?.let { document -> Decoded(BitwardenCipher.Environment(document = document), remaining) }
    }
}
