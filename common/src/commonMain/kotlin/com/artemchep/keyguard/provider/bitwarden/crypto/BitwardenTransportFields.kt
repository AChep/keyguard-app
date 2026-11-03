package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon

/**
 * Keyguard's own data that a Bitwarden item has no member for, carried in its
 * custom fields: an environment, a GPG key, the bookmarks and certificate of
 * an SSH key, the PRF secrets of passkeys, tags, the KeePass icon and the links
 * to other items.
 *
 * The sync, the JSON export and the import all read and write that data here.
 * Each part is read and written only on the types that may carry it, so user
 * fields with matching names on other types stay untouched.
 */
internal object BitwardenTransportFields {
    const val TAG_FIELD_NAME = "Tag"

    /** Keyguard's own data of an item, see [encode]. */
    data class Payload(
        val customIcon: KeePassIcon? = null,
        val environment: BitwardenCipher.Environment? = null,
        val gpgKey: BitwardenCipher.GpgKey? = null,
        val bookmarks: List<BitwardenCipher.SshKey.Bookmark> = emptyList(),
        val certificate: String? = null,
        val passkeys: List<BitwardenPasskeyFieldCodec.Secrets> = emptyList(),
        /** The remote ids of the linked items. */
        val links: List<String> = emptyList(),
    )

    data class Encoded(
        val fields: List<BitwardenCipher.Field>,
        /** The bookmarks are in the [fields], so the SSH key must not carry them. */
        val bookmarks: Boolean,
        /** The certificate is in the [fields], so the SSH key must not carry it. */
        val certificate: Boolean,
        /** The PRF secrets are in the [fields], so the passkeys must not carry them. */
        val passkeys: Boolean,
    )

    data class Decoded(
        /** The type the fields make the item: a secure note may carry an environment or a GPG key. */
        val type: BitwardenCipher.Type,
        /** The user's own fields. */
        val fields: List<BitwardenCipher.Field>,
        val environment: BitwardenCipher.Environment? = null,
        val gpgKey: BitwardenCipher.GpgKey? = null,
        val bookmarks: List<BitwardenCipher.SshKey.Bookmark> = emptyList(),
        val certificate: String? = null,
        /** The passkeys with their PRF secrets, or `null` if no passkey document decoded. */
        val credentials: List<BitwardenCipher.Login.Fido2Credentials>? = null,
        val tags: List<String> = emptyList(),
        /** The icon of the icon field, or `null` if there is none. */
        val icon: KeePassIcon? = null,
        /** The remote ids of the linked items. */
        val links: List<String> = emptyList(),
    ) {
        /**
         * The icon the item shows. [KeePassIcon.Key] is the default icon, which
         * no field is written for, so it reads as none.
         */
        val customIcon: KeePassIcon?
            get() = icon?.takeUnless { it == KeePassIcon.Key }
    }

    /**
     * Writes the [payload] into the [fields] of an item of [type], in a fixed
     * order: the GPG key, the icon, the environment, the user's fields, the
     * bookmarks, the certificate, the PRF secrets and the links.
     *
     * [chunkedGpgKey] `false` writes the GPG key as the JSON export always
     * has, see [BitwardenGpgKeyFieldCodec.encode].
     */
    fun encode(
        type: BitwardenCipher.Type?,
        fields: List<BitwardenCipher.Field>,
        payload: Payload,
        chunkedGpgKey: Boolean = true,
    ): Encoded {
        fun appliesTo(predicate: (BitwardenCipher.Type) -> Boolean) = type != null && predicate(type)

        var result = fields
        val environment = payload.environment
            ?.takeIf { appliesTo(BitwardenEnvironmentFieldCodec::appliesTo) }
        if (environment != null) {
            result = BitwardenEnvironmentFieldCodec.encode(environment) +
                    result.filterNot { BitwardenEnvironmentFieldCodec.isTransportField(it.name) }
        }
        result = BitwardenIconFieldCodec.encode(payload.customIcon, result)
        val gpgKey = payload.gpgKey
            ?.takeIf { appliesTo(BitwardenGpgKeyFieldCodec::appliesTo) }
        if (gpgKey != null) {
            result = BitwardenGpgKeyFieldCodec.encode(gpgKey, result, chunked = chunkedGpgKey)
        }
        val bookmarks = payload.bookmarks
            .takeIf { appliesTo(BitwardenSshBookmarkFieldCodec::appliesTo) }
            .orEmpty()
        result = result + BitwardenSshBookmarkFieldCodec.encode(bookmarks)
        // The certificate, the PRF secrets and the links are auxiliary
        // data, append them after the user's own custom fields.
        val certificate = payload.certificate
            ?.takeIf { appliesTo(BitwardenSshCertificateFieldCodec::appliesTo) && it.isNotBlank() }
        if (certificate != null) {
            result = result.filterNot { BitwardenSshCertificateFieldCodec.isTransportField(it.name) } +
                    BitwardenSshCertificateFieldCodec.encode(certificate)
        }
        val passkeyFields = if (appliesTo(BitwardenPasskeyFieldCodec::appliesTo)) {
            BitwardenPasskeyFieldCodec.encode(payload.passkeys, result)
        } else {
            emptyList()
        }
        if (passkeyFields.isNotEmpty()) {
            result = result.filterNot { BitwardenPasskeyFieldCodec.isTransportField(it.name) } + passkeyFields
        }
        result = result + payload.links.toLinkFields()
        return Encoded(
            fields = result,
            bookmarks = bookmarks.isNotEmpty(),
            certificate = certificate != null,
            passkeys = passkeyFields.isNotEmpty(),
        )
    }

    /**
     * Reads Keyguard's own data out of the [fields] of an item of [type]. The
     * PRF secrets are read into the [credentials], if the item has a login.
     * A group of fields that does not decode stays among the user's fields.
     */
    fun decode(
        type: BitwardenCipher.Type,
        fields: List<BitwardenCipher.Field>,
        credentials: List<BitwardenCipher.Login.Fido2Credentials>?,
    ): Decoded {
        val certificate = if (BitwardenSshCertificateFieldCodec.appliesTo(type)) {
            BitwardenSshCertificateFieldCodec.decode(fields)
        } else {
            null
        }
        val certificateFields = certificate?.remainingFields ?: fields
        val passkeys = if (credentials != null && BitwardenPasskeyFieldCodec.appliesTo(type)) {
            BitwardenPasskeyFieldCodec.decode(certificateFields, credentials)
        } else {
            null
        }
        val decoded = decodeType(type, passkeys?.remainingFields ?: certificateFields)
        val scope = FieldDecodeScope(decoded.fields)
        val tags = scope.consumeAll(::isTag)
            .mapNotNull { it.value }
        val icon = scope.consumeFirstDecoded(BitwardenIconFieldCodec::decodeField)
        val links = scope.consumeAllDecoded { it.decodeCipherLinkFieldOrNull() }
            .let(CipherLinkFields::decode)
            .map { it.remoteCipherId }
        val bookmarks = if (BitwardenSshBookmarkFieldCodec.appliesTo(type)) {
            scope.consumeAllDecoded(BitwardenSshBookmarkFieldCodec::decode)
        } else {
            emptyList()
        }
        return decoded.copy(
            fields = scope.remainingFields,
            bookmarks = bookmarks,
            certificate = certificate?.certificate,
            credentials = passkeys?.credentials,
            tags = tags,
            icon = icon,
            links = links,
        )
    }

    /**
     * Reads only the type the [fields] make an item of [type]: an environment,
     * or else a GPG key.
     */
    fun decodeType(
        type: BitwardenCipher.Type,
        fields: List<BitwardenCipher.Field>,
    ): Decoded {
        val environment = if (BitwardenEnvironmentFieldCodec.appliesTo(type)) {
            BitwardenEnvironmentFieldCodec.decode(fields)
        } else {
            null
        }
        val gpgKey = if (environment == null && BitwardenGpgKeyFieldCodec.appliesTo(type)) {
            BitwardenGpgKeyFieldCodec.decode(fields)
        } else {
            null
        }
        return Decoded(
            type = when {
                environment != null -> BitwardenCipher.Type.Environment
                gpgKey != null -> BitwardenCipher.Type.GpgKey
                else -> type
            },
            fields = environment?.remainingFields ?: gpgKey?.remainingFields ?: fields,
            environment = environment?.environment,
            gpgKey = gpgKey?.gpgKey,
        )
    }

    // Keyguard writes a field per tag, never a blank one.
    private fun isTag(field: BitwardenCipher.Field): Boolean =
        field.name == TAG_FIELD_NAME &&
                field.type == BitwardenCipher.Field.Type.Text &&
                field.linkedId == null &&
                !field.value.isNullOrBlank()

    private fun List<String>.toLinkFields(): List<BitwardenCipher.Field> =
        CipherLinkFields
            .format(this)
            .map { (name, value) ->
                BitwardenCipher.Field(
                    name = name,
                    value = value,
                    type = BitwardenCipher.Field.Type.Text,
                )
            }

    private fun BitwardenCipher.Field.decodeCipherLinkFieldOrNull(): CipherLinkFields.ParsedField? {
        if (linkedId != null || type != BitwardenCipher.Field.Type.Text) {
            return null
        }
        return CipherLinkFields.parse(
            name = name,
            value = value,
        )
    }
}

/**
 * Tracks fields claimed by the individual decoders while preserving the
 * original order and duplicate field instances for the final remainder.
 */
private class FieldDecodeScope(
    private val source: List<BitwardenCipher.Field>,
) {
    private val consumed = BooleanArray(source.size)

    val remainingFields: List<BitwardenCipher.Field>
        get() = source.filterIndexed { index, _ -> !consumed[index] }

    fun consumeAll(
        predicate: (BitwardenCipher.Field) -> Boolean,
    ): List<BitwardenCipher.Field> = consumeAllDecoded { field ->
        field.takeIf(predicate)
    }

    fun <T : Any> consumeFirstDecoded(
        decode: (BitwardenCipher.Field) -> T?,
    ): T? = source.indices.firstNotNullOfOrNull { index -> consumeOrNull(index, decode) }

    fun <T : Any> consumeAllDecoded(
        decode: (BitwardenCipher.Field) -> T?,
    ): List<T> = source.indices.mapNotNull { index -> consumeOrNull(index, decode) }

    /** Claims the field at [index] if it is not claimed yet and [decode]s. */
    private fun <T : Any> consumeOrNull(
        index: Int,
        decode: (BitwardenCipher.Field) -> T?,
    ): T? = if (consumed[index]) {
        null
    } else {
        decode(source[index])?.also { consumed[index] = true }
    }
}
