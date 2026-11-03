package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Carries the certificate of an SSH key in a Bitwarden custom field: the
 * Bitwarden SSH key has no field of its own for it. A certificate that does
 * not fit one field is split into [BitwardenChunkedFields]. A group that does
 * not decode is not a certificate: the item keeps its fields as they are, as
 * with GPG keys.
 */
internal object BitwardenSshCertificateFieldCodec {
    const val FIELD_NAME = "keyguard.ssh.certificate"

    private val CHUNK_PREFIX = BitwardenChunkedFields.prefix(FIELD_NAME)

    // A certificate is public, so it stays readable in other Bitwarden clients.
    private val FIELD_TYPE = BitwardenCipher.Field.Type.Text

    data class Decoded(
        val certificate: String,
        val remainingFields: List<BitwardenCipher.Field>,
    )

    /**
     * Only SSH keys carry a certificate. Other types must keep
     * user-created fields with matching names untouched.
     */
    fun appliesTo(type: BitwardenCipher.Type): Boolean =
        type == BitwardenCipher.Type.SshKey

    fun isTransportField(name: String?): Boolean =
        name == FIELD_NAME || name?.startsWith(CHUNK_PREFIX) == true

    fun encode(certificate: String): List<BitwardenCipher.Field> =
        BitwardenChunkedFields.encodeSingleOrChunked(FIELD_NAME, certificate, FIELD_TYPE)

    /** Returns `null` if the [fields] carry no intact certificate. */
    fun decode(fields: List<BitwardenCipher.Field>): Decoded? {
        val (transport, remaining) = fields.partition { isTransportField(it.name) }
        return BitwardenChunkedFields.decodeSingleOrChunked(FIELD_NAME, transport, FIELD_TYPE)
            ?.takeIf { it.isNotBlank() }
            ?.let { Decoded(it, remaining) }
    }
}
