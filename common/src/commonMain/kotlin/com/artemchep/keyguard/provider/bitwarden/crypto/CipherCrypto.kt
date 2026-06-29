package com.artemchep.keyguard.provider.bitwarden.crypto

import kotlin.jvm.JvmName

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon

fun BitwardenCipher.transform(
    itemCrypto: BitwardenCrCta,
    globalCrypto: BitwardenCrCta,
): BitwardenCipher {
    val sourceCipher = encodeKeePassIconCustomField(
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

private fun decodeEntity(
    cipher: BitwardenCipher,
): BitwardenCipher {
    fun isTag(field: BitwardenCipher.Field): Boolean {
        return field.type == BitwardenCipher.Field.Type.Text &&
                field.name == "Tag"
    }

    val fieldsWithoutTags = cipher.fields
        .filter { !isTag(it) }
    val tags = cipher.fields
        .filter { isTag(it) }
        .mapNotNull {
            val name = it.value
                ?: return@mapNotNull null
            BitwardenCipher.Tag(name)
        }
    val decodedIcon = fieldsWithoutTags.decodeKeePassIconCustomField()
    return cipher.copy(
        fields = decodedIcon.fields,
        tags = tags,
        customIcon = if (decodedIcon.consumed) {
            decodedIcon.keepassIcon
        } else {
            cipher.customIcon
        },
    )
}

private data class DecodedKeePassIconCustomField(
    val fields: List<BitwardenCipher.Field>,
    val keepassIcon: KeePassIcon?,
    val consumed: Boolean,
)

private fun List<BitwardenCipher.Field>.decodeKeePassIconCustomField(): DecodedKeePassIconCustomField {
    var consumed = false
    var keepassIcon: KeePassIcon? = null
    val remainingFields = buildList {
        this@decodeKeePassIconCustomField.forEach { field ->
            val decodedIcon = field.decodeKeePassIconOrNull()
            if (!consumed && decodedIcon != null) {
                consumed = true
                keepassIcon = decodedIcon.takeUnless { it == KeePassIcon.Key }
            } else {
                add(field)
            }
        }
    }
    return DecodedKeePassIconCustomField(
        fields = remainingFields,
        keepassIcon = keepassIcon,
        consumed = consumed,
    )
}

private fun BitwardenCipher.Field.decodeKeePassIconOrNull(): KeePassIcon? {
    if (name != KEEPASS_ICON_FIELD_NAME) return null
    if (type != BitwardenCipher.Field.Type.Text) return null
    if (linkedId != null) return null
    val rawValue = value ?: return null
    return KeePassIcon.entries.firstOrNull { icon -> icon.name == rawValue }
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
