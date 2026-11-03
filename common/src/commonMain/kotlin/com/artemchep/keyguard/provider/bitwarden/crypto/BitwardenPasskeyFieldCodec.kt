package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Carries the passkey data that the Bitwarden passkey has no field for, the
 * secrets of the WebAuthn PRF extension, in a hidden custom field of the login.
 * A document that does not fit one field is split into [BitwardenChunkedFields].
 * A group that does not decode stays a custom field, as with GPG keys.
 */
internal object BitwardenPasskeyFieldCodec {
    const val FIELD_NAME = "keyguard.passkeys"

    /**
     * The readers ignore unknown keys, so a new key keeps this version. A change
     * that older readers would misread needs a new field name instead: a version
     * replaces a document it can not read once it has secrets to write.
     */
    private const val VERSION = 1

    private val CHUNK_PREFIX = BitwardenChunkedFields.prefix(FIELD_NAME)

    private val FIELD_TYPE = BitwardenCipher.Field.Type.Hidden

    private val writer = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val reader = Json(writer) { ignoreUnknownKeys = true }

    @Serializable
    private data class Document(
        val version: Int,
        val credentials: List<Credential> = emptyList(),
    )

    @Serializable
    private data class Credential(
        val credentialId: String,
        val prfSecret: String? = null,
        val prfSecretWithoutUv: String? = null,
    )

    /** The PRF secrets of a passkey. */
    data class Secrets(
        val credentialId: String?,
        val prfSecret: String?,
        val prfSecretWithoutUv: String? = null,
    )

    data class Decoded(
        val credentials: List<BitwardenCipher.Login.Fido2Credentials>,
        val remainingFields: List<BitwardenCipher.Field>,
    )

    /**
     * Only logins carry passkeys. Other types must keep
     * user-created fields with matching names untouched.
     */
    fun appliesTo(type: BitwardenCipher.Type): Boolean =
        type == BitwardenCipher.Type.Login

    fun isTransportField(name: String?): Boolean =
        name == FIELD_NAME || name?.startsWith(CHUNK_PREFIX) == true

    /**
     * Returns no fields if there are no PRF secrets to write. A passkey without
     * a credential ID has nothing to key its secrets by and is skipped.
     *
     * A passkey without secrets keeps those of the document among the [fields],
     * so a document that was never decoded, such as one that an older version
     * kept as a plain custom field, does not lose the secrets of other passkeys.
     */
    fun encode(
        passkeys: List<Secrets>,
        fields: List<BitwardenCipher.Field>,
    ): List<BitwardenCipher.Field> {
        val existing = decodeEntriesOrNull(fields.filter { isTransportField(it.name) })
            .orEmpty()
        val entries = passkeys.mapNotNull { passkey ->
            val credentialId = passkey.credentialId
                ?: return@mapNotNull null
            if (passkey.prfSecret != null || passkey.prfSecretWithoutUv != null) {
                Credential(
                    credentialId = credentialId,
                    prfSecret = passkey.prfSecret,
                    prfSecretWithoutUv = passkey.prfSecretWithoutUv,
                )
            } else {
                existing[credentialId]
            }
        }
        if (entries.isEmpty()) return emptyList()

        val document = writer.encodeToString(Document(version = VERSION, credentials = entries))
        return BitwardenChunkedFields.encodeSingleOrChunked(FIELD_NAME, document, FIELD_TYPE)
    }

    /**
     * Returns `null` if the [fields] carry no intact document. The entries of
     * passkeys that are no longer among the [credentials] are dropped.
     */
    fun decode(
        fields: List<BitwardenCipher.Field>,
        credentials: List<BitwardenCipher.Login.Fido2Credentials>,
    ): Decoded? {
        val (transport, remaining) = fields.partition { isTransportField(it.name) }
        val entries = decodeEntriesOrNull(transport)
            ?: return null
        return Decoded(
            credentials = credentials.map { credential ->
                val entry = credential.credentialId
                    ?.let(entries::get)
                    ?: return@map credential
                credential.copy(
                    prfSecret = entry.prfSecret,
                    prfSecretWithoutUv = entry.prfSecretWithoutUv,
                )
            },
            remainingFields = remaining,
        )
    }

    /** The entries of the document that the [transport] fields carry, by credential ID. */
    private fun decodeEntriesOrNull(
        transport: List<BitwardenCipher.Field>,
    ): Map<String, Credential>? = BitwardenChunkedFields
        .decodeSingleOrChunked(FIELD_NAME, transport, FIELD_TYPE)
        ?.let(::decodeDocumentOrNull)
        ?.credentials
        ?.associateBy { it.credentialId }

    private fun decodeDocumentOrNull(value: String): Document? = try {
        reader.decodeFromString<Document>(value)
            .takeIf { it.version == VERSION }
    } catch (_: IllegalArgumentException) {
        null
    }
}
