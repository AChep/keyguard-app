package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.AttachmentEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.domain

fun BitwardenCipher.Companion.encrypted(
    accountId: String,
    cipherId: String,
    folderId: String?,
    entity: CipherEntity,
) = kotlin.run {
    val service = BitwardenService(
        remote = BitwardenService.Remote(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
        ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
    BitwardenCipher(
        accountId = accountId,
        cipherId = cipherId,
        folderId = folderId,
        organizationId = entity.organizationId,
        collectionIds = entity.collectionIds?.toSet().orEmpty(),
        createdDate = entity.creationDate,
        revisionDate = entity.revisionDate,
        deletedDate = entity.deletedDate,
        keyBase64 = entity.key,
        // service fields
        service = service,
        // common
        name = entity.name,
        notes = entity.notes,
        favorite = entity.favorite,
        fields = entity.fields
            .orEmpty()
            .map {
                val type = it.type.domain()
                val linkedId = it.linkedId?.domain()
                BitwardenCipher.Field(
                    name = it.name,
                    value = it.value,
                    type = type,
                    linkedId = linkedId,
                )
            },
        attachments = entity.attachments
            .orEmpty()
            .map { attachment ->
                BitwardenCipher.Attachment.encrypted(
                    attachment = attachment,
                )
            },
        // types
        type = entity.type.domain(),
        reprompt = entity.reprompt.domain(),
        login = entity.login
            ?.run {
                BitwardenCipher.Login(
                    username = username,
                    password = password,
                    passwordRevisionDate = passwordRevisionDate,
                    passwordHistory = entity.passwordHistory
                        .orEmpty()
                        .map {
                            BitwardenCipher.Login.PasswordHistory(
                                password = it.password,
                                lastUsedDate = it.lastUsedDate,
                            )
                        },
                    totp = totp,
                    uris = uris
                        .orEmpty()
                        .map {
                            val match = it.match?.domain()
                            BitwardenCipher.Login.Uri(
                                uri = it.uri,
                                uriChecksumBase64 = it.uriChecksum,
                                match = match,
                            )
                        },
                    fido2Credentials = fido2Credentials
                        .orEmpty()
                        .map {
                            BitwardenCipher.Login.Fido2Credentials(
                                credentialId = it.credentialId,
                                keyType = it.keyType,
                                keyAlgorithm = it.keyAlgorithm,
                                keyCurve = it.keyCurve,
                                keyValue = it.keyValue,
                                rpId = it.rpId,
                                rpName = it.rpName,
                                counter = it.counter,
                                userHandle = it.userHandle,
                                userName = it.userName,
                                userDisplayName = it.userDisplayName,
                                discoverable = it.discoverable,
                                creationDate = it.creationDate,
                            )
                        },
                )
            },
        secureNote = entity.secureNote
            ?.run {
                BitwardenCipher.SecureNote(
                    type = type.domain(),
                )
            },
        card = entity.card
            ?.run {
                BitwardenCipher.Card(
                    cardholderName = cardholderName,
                    brand = brand,
                    number = number,
                    expMonth = expMonth,
                    expYear = expYear,
                    code = code,
                )
            },
        identity = entity.identity
            ?.run {
                BitwardenCipher.Identity(
                    title = title,
                    firstName = firstName,
                    middleName = middleName,
                    lastName = lastName,
                    address1 = address1,
                    address2 = address2,
                    address3 = address3,
                    city = city,
                    state = state,
                    postalCode = postalCode,
                    country = country,
                    company = company,
                    email = email,
                    phone = phone,
                    ssn = ssn,
                    username = username,
                    passportNumber = passportNumber,
                    licenseNumber = licenseNumber,
                )
            },
    )
}

fun BitwardenCipher.Attachment.Companion.encrypted(
    attachment: AttachmentEntity,
) = kotlin.run {
    BitwardenCipher.Attachment.Remote(
        id = attachment.id,
        url = attachment.url,
        fileName = attachment.fileName,
        keyBase64 = attachment.key,
        size = attachment.size.toLong(),
    )
}
