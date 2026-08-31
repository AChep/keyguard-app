package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import kotlin.jvm.JvmName

fun BitwardenCipher.transform(
    itemCrypto: BitwardenCrCta,
    globalCrypto: BitwardenCrCta,
): BitwardenCipher {
    val sourceCipher = encodeKeePassIconCustomField(
        mode = itemCrypto.mode,
    ).encodeGpgKeyCustomFields(
        mode = itemCrypto.mode,
    ).encodeCipherLinkCustomFields(
        mode = itemCrypto.mode,
    )
    return sourceCipher.copy(
        // common
        keyBase64 = sourceCipher.keyBase64?.let(globalCrypto::transformBase64),
        name = itemCrypto.transformString(sourceCipher.name),
        notes = itemCrypto.transformString(sourceCipher.notes),
        tags = sourceCipher.tags.transform(itemCrypto),
        fields = sourceCipher.fields.transform(itemCrypto),
        attachments = sourceCipher.attachments.transform(itemCrypto),
        passwordHistory = sourceCipher.passwordHistory.transform(itemCrypto),
        remoteEntity = sourceCipher.remoteEntity
            ?.transform(
                itemCrypto = itemCrypto,
                globalCrypto = globalCrypto,
            ),
        // types
        login = sourceCipher.login?.transform(itemCrypto),
        secureNote = sourceCipher.secureNote?.transform(itemCrypto),
        card = sourceCipher.card?.transform(itemCrypto),
        identity = sourceCipher.identity?.transform(itemCrypto),
        sshKey = sourceCipher.sshKey?.transform(itemCrypto),
        gpgKey = if (itemCrypto.mode == BitwardenCrCta.Mode.ENCRYPT) {
            null
        } else {
            sourceCipher.gpgKey
        },
        links = if (itemCrypto.mode == BitwardenCrCta.Mode.ENCRYPT) {
            emptyList()
        } else {
            sourceCipher.links
        },
    ).let { transformedCipher ->
        if (globalCrypto.mode == BitwardenCrCta.Mode.DECRYPT) {
            return@let decodeEntity(transformedCipher)
        }

        transformedCipher
    }
}

private const val KEEPASS_ICON_FIELD_NAME = "Custom Icon Name"

private fun BitwardenCipher.encodeKeePassIconCustomField(
    mode: BitwardenCrCta.Mode,
): BitwardenCipher {
    if (mode != BitwardenCrCta.Mode.ENCRYPT) return this
    val icon = customIcon
        ?.takeUnless { it == KeePassIcon.Key }
        ?: return this

    val reservedNames = fields
        .asSequence()
        .mapNotNull { it.name }
        .filter { it != KEEPASS_ICON_FIELD_NAME }
        .toMutableSet()
    reservedNames += KEEPASS_ICON_FIELD_NAME

    fun nextAvailableIconFieldName(): String {
        var index = 1
        while (true) {
            val name = "$KEEPASS_ICON_FIELD_NAME #$index"
            if (reservedNames.add(name)) return name
            index++
        }
    }

    val iconField = BitwardenCipher.Field(
        name = KEEPASS_ICON_FIELD_NAME,
        value = icon.name,
        type = BitwardenCipher.Field.Type.Text,
    )
    val renamedFields = fields.map { field ->
        if (field.name == KEEPASS_ICON_FIELD_NAME) {
            field.copy(name = nextAvailableIconFieldName())
        } else {
            field
        }
    }
    return copy(fields = listOf(iconField) + renamedFields)
}

private fun BitwardenCipher.encodeGpgKeyCustomFields(
    mode: BitwardenCrCta.Mode,
): BitwardenCipher {
    if (mode != BitwardenCrCta.Mode.ENCRYPT) return this
    if (!BitwardenGpgKeyFieldCodec.appliesTo(type)) return this
    val gpgKey = gpgKey
        ?: return this
    return copy(
        fields = BitwardenGpgKeyFieldCodec.encode(
            gpgKey = gpgKey,
            fields = fields,
        ),
    )
}

private fun BitwardenCipher.encodeCipherLinkCustomFields(
    mode: BitwardenCrCta.Mode,
): BitwardenCipher {
    if (mode != BitwardenCrCta.Mode.ENCRYPT) return this
    val linkFields = links.toFields()
    // The links are auxiliary data, append them after the user's own custom fields.
    return if (linkFields.isEmpty()) this else copy(fields = fields + linkFields)
}

private fun List<BitwardenCipher.Link>.toFields(): List<BitwardenCipher.Field> =
    CipherLinkFields
        .format(map(BitwardenCipher.Link::remoteCipherId))
        .map { (name, value) ->
            BitwardenCipher.Field(
                name = name,
                value = value,
                type = BitwardenCipher.Field.Type.Text,
            )
        }

private fun decodeEntity(
    cipher: BitwardenCipher,
): BitwardenCipher {
    fun isTag(field: BitwardenCipher.Field): Boolean {
        return field.type == BitwardenCipher.Field.Type.Text &&
                field.name == "Tag"
    }

    val decodedGpg = if (BitwardenGpgKeyFieldCodec.appliesTo(cipher.type)) {
        BitwardenGpgKeyFieldCodec.decode(
            fields = cipher.fields,
        )
    } else {
        null
    }
    val fields = CipherFieldDecodeScope(
        source = decodedGpg?.remainingFields ?: cipher.fields,
    )
    val tags = fields
        .consumeAll(::isTag)
        .mapNotNull {
            val name = it.value
                ?: return@mapNotNull null
            BitwardenCipher.Tag(name)
        }
    val decodedIcon = fields.consumeFirstDecoded { field ->
        field.decodeKeePassIconOrNull()
    }
    val decodedLinks = fields.decodeCipherLinkCustomFields()
    return cipher.copy(
        fields = fields.remainingFields,
        links = decodedLinks,
        tags = tags,
        customIcon = when (decodedIcon) {
            null -> cipher.customIcon
            KeePassIcon.Key -> null
            else -> decodedIcon
        },
        type = if (decodedGpg != null) {
            BitwardenCipher.Type.GpgKey
        } else {
            cipher.type
        },
        gpgKey = decodedGpg?.gpgKey ?: cipher.gpgKey,
    )
}

/**
 * Tracks fields claimed by the individual decoders while preserving the
 * original order and duplicate field instances for the final remainder.
 */
private class CipherFieldDecodeScope(
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
    ): T? {
        for (index in source.indices) {
            if (consumed[index]) continue

            val value = decode(source[index])
                ?: continue
            consumed[index] = true
            return value
        }
        return null
    }

    fun <T : Any> consumeAllDecoded(
        decode: (BitwardenCipher.Field) -> T?,
    ): List<T> = buildList {
        for (index in source.indices) {
            if (consumed[index]) continue

            val value = decode(source[index])
                ?: continue
            consumed[index] = true
            add(value)
        }
    }
}

private fun BitwardenCipher.Field.decodeKeePassIconOrNull(): KeePassIcon? {
    if (name != KEEPASS_ICON_FIELD_NAME) return null
    if (type != BitwardenCipher.Field.Type.Text) return null
    if (linkedId != null) return null
    val rawValue = value ?: return null
    return KeePassIcon.entries.firstOrNull { icon -> icon.name == rawValue }
}

private fun CipherFieldDecodeScope.decodeCipherLinkCustomFields(): List<BitwardenCipher.Link> {
    val decodedFields = consumeAllDecoded { field ->
        field.decodeCipherLinkFieldOrNull()
    }
    return CipherLinkFields
        .decode(decodedFields)
        .map { link ->
            BitwardenCipher.Link(
                remoteCipherId = link.remoteCipherId,
            )
        }
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

@JvmName("encryptListOfBitwardenCipherAttachment")
fun List<BitwardenCipher.Attachment>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Attachment.transform(
    crypto: BitwardenCrCta,
) = when (this) {
    is BitwardenCipher.Attachment.Remote -> transform(crypto)
    is BitwardenCipher.Attachment.Local -> transform(crypto)
}

fun BitwardenCipher.Attachment.Remote.transform(
    crypto: BitwardenCrCta,
) = copy(
    fileName = crypto.transformString(fileName),
    keyBase64 = keyBase64?.let(crypto::transformBase64),
)

fun BitwardenCipher.Attachment.Local.transform(
    crypto: BitwardenCrCta,
) = this

@JvmName("encryptListOfBitwardenTagField")
fun List<BitwardenCipher.Tag>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Tag.transform(
    crypto: BitwardenCrCta,
) = copy(
    name = crypto.transformString(name),
)

@JvmName("encryptListOfBitwardenCipherField")
fun List<BitwardenCipher.Field>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Field.transform(
    crypto: BitwardenCrCta,
) = copy(
    name = crypto.transformString(name),
    value = crypto.transformString(value),
)

fun BitwardenCipher.Login.transform(
    crypto: BitwardenCrCta,
) = copy(
    username = crypto.transformString(username),
    password = crypto.transformString(password),
    uris = uris.map { uri -> uri.transform(crypto) },
    fido2Credentials = fido2Credentials.map { credentials -> credentials.transform(crypto) },
    totp = crypto.transformString(totp),
)

@JvmName("encryptListOfBitwardenCipherLoginPasswordHistory")
fun List<BitwardenCipher.Login.PasswordHistory>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Login.PasswordHistory.transform(
    crypto: BitwardenCrCta,
) = copy(
    password = crypto.transformString(password),
)

@JvmName("encryptListOfBitwardenCipherLoginUri")
fun List<BitwardenCipher.Login.Uri>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Login.Uri.transform(
    crypto: BitwardenCrCta,
) = copy(
    uri = crypto.transformString(uri.orEmpty()),
    uriChecksumBase64 = uriChecksumBase64?.let(crypto::transformString),
    signatures = signatures.map { signature ->
        signature.transform(crypto)
    },
)

fun BitwardenCipher.Login.Uri.Signature.transform(
    crypto: BitwardenCrCta,
) = copy(
    certFingerprintSha256 = crypto.transformString(certFingerprintSha256),
)

@JvmName("encryptListOfBitwardenCipherLoginFido2Credentials")
fun List<BitwardenCipher.Login.Fido2Credentials>.transform(
    crypto: BitwardenCrCta,
) = map { item -> item.transform(crypto) }

fun BitwardenCipher.Login.Fido2Credentials.transform(
    crypto: BitwardenCrCta,
) = copy(
    credentialId = crypto.transformString(credentialId),
    keyType = crypto.transformString(keyType),
    keyAlgorithm = crypto.transformString(keyAlgorithm),
    keyCurve = crypto.transformString(keyCurve),
    keyValue = crypto.transformString(keyValue),
    rpId = crypto.transformString(rpId),
    rpName = crypto.transformString(rpName),
    counter = crypto.transformString(counter),
    userHandle = crypto.transformString(userHandle),
    userName = crypto.transformString(userName),
    userDisplayName = crypto.transformString(userDisplayName),
    discoverable = crypto.transformString(discoverable),
)

fun BitwardenCipher.SecureNote.transform(
    crypto: BitwardenCrCta,
) = this // Does not need encryption

fun BitwardenCipher.Identity.transform(
    crypto: BitwardenCrCta,
) = copy(
    title = crypto.transformString(title),
    firstName = crypto.transformString(firstName),
    middleName = crypto.transformString(middleName),
    lastName = crypto.transformString(lastName),
    address1 = crypto.transformString(address1),
    address2 = crypto.transformString(address2),
    address3 = crypto.transformString(address3),
    city = crypto.transformString(city),
    state = crypto.transformString(state),
    postalCode = crypto.transformString(postalCode),
    country = crypto.transformString(country),
    company = crypto.transformString(company),
    email = crypto.transformString(email),
    phone = crypto.transformString(phone),
    ssn = crypto.transformString(ssn),
    username = crypto.transformString(username),
    passportNumber = crypto.transformString(passportNumber),
    licenseNumber = crypto.transformString(licenseNumber),
)

fun BitwardenCipher.Card.transform(
    crypto: BitwardenCrCta,
) = copy(
    cardholderName = crypto.transformString(cardholderName),
    brand = crypto.transformString(brand),
    number = crypto.transformString(number),
    expMonth = crypto.transformString(expMonth),
    expYear = crypto.transformString(expYear),
    code = crypto.transformString(code),
)

fun BitwardenCipher.SshKey.transform(
    crypto: BitwardenCrCta,
) = copy(
    privateKey = crypto.transformString(privateKey),
    publicKey = crypto.transformString(publicKey),
    fingerprint = crypto.transformString(fingerprint),
)
