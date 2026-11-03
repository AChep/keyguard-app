package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.jvm.JvmName

fun BitwardenCipher.transform(
    itemCrypto: BitwardenCrCta,
    globalCrypto: BitwardenCrCta,
): BitwardenCipher {
    val sourceCipher = encodeTransportFields(
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
        environment = if (itemCrypto.mode == BitwardenCrCta.Mode.ENCRYPT) null else sourceCipher.environment,
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
            return@let transformedCipher.decodeTransportFields()
        }

        transformedCipher
    }
}

/** Moves Keyguard's own data into the custom fields, see [BitwardenTransportFields]. */
private fun BitwardenCipher.encodeTransportFields(
    mode: BitwardenCrCta.Mode,
): BitwardenCipher {
    if (mode != BitwardenCrCta.Mode.ENCRYPT) return this
    val encoded = BitwardenTransportFields.encode(
        type = type,
        fields = fields,
        payload = BitwardenTransportFields.Payload(
            customIcon = customIcon,
            environment = environment,
            gpgKey = gpgKey,
            bookmarks = sshKey?.bookmarks.orEmpty(),
            certificate = sshKey?.certificate,
            passkeys = login?.fido2Credentials
                .orEmpty()
                .map { credential ->
                    BitwardenPasskeyFieldCodec.Secrets(
                        credentialId = credential.credentialId,
                        prfSecret = credential.prfSecret,
                        prfSecretWithoutUv = credential.prfSecretWithoutUv,
                    )
                },
            links = links.map(BitwardenCipher.Link::remoteCipherId),
        ),
    )
    return copy(
        fields = encoded.fields,
        // The Bitwarden SSH key has no bookmarks or certificate fields.
        sshKey = sshKey?.copy(
            bookmarks = if (encoded.bookmarks) emptyList() else sshKey.bookmarks,
            certificate = if (encoded.certificate) null else sshKey.certificate,
        ),
        // The Bitwarden passkey has no PRF secret fields.
        login = if (encoded.passkeys) {
            login?.copy(
                fido2Credentials = login.fido2Credentials.map {
                    it.copy(
                        prfSecret = null,
                        prfSecretWithoutUv = null,
                    )
                },
            )
        } else {
            login
        },
    )
}

/** Moves Keyguard's own data out of the custom fields, see [BitwardenTransportFields]. */
private fun BitwardenCipher.decodeTransportFields(): BitwardenCipher {
    val decoded = BitwardenTransportFields.decode(
        type = type,
        fields = fields,
        credentials = login?.fido2Credentials,
    )
    return copy(
        type = decoded.type,
        fields = decoded.fields,
        links = decoded.links.map(BitwardenCipher::Link),
        tags = decoded.tags.map(BitwardenCipher::Tag),
        customIcon = if (decoded.icon != null) decoded.customIcon else customIcon,
        environment = decoded.environment ?: environment,
        gpgKey = decoded.gpgKey ?: gpgKey,
        sshKey = if (decoded.certificate != null || decoded.bookmarks.isNotEmpty()) {
            (sshKey ?: BitwardenCipher.SshKey()).copy(
                certificate = decoded.certificate ?: sshKey?.certificate,
                bookmarks = decoded.bookmarks.ifEmpty { sshKey?.bookmarks.orEmpty() },
            )
        } else {
            sshKey
        },
        login = decoded.credentials
            ?.let { credentials -> login?.copy(fido2Credentials = credentials) }
            ?: login,
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
    prfSecret = crypto.transformString(prfSecret),
    prfSecretWithoutUv = crypto.transformString(prfSecretWithoutUv),
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
