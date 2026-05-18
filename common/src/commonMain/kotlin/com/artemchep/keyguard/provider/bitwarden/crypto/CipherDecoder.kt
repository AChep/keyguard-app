package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.AttachmentEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CardEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherDataEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.IdentityEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LoginEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SecureNoteEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SecureNoteTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SshKeyEntity
import com.artemchep.keyguard.provider.bitwarden.entity.domain

fun BitwardenCipher.Companion.encrypted(
    accountId: String,
    cipherId: String,
    folderId: String?,
    entity: CipherEntity,
) = kotlin.run {
    val data = entity.data

    // Get the fields from the data object if that does exist, otherwise
    // get it from the legacy top-level arguments.
    val fields = if (data != null) {
        data.fields.orEmpty()
    } else entity.fields.orEmpty()
    val passwordHistory = if (data != null) {
        data.passwordHistory.orEmpty()
    } else entity.passwordHistory.orEmpty()
    val login = if (data != null && entity.type == CipherTypeEntity.Login) {
        data.toLoginEntity()
    } else entity.login
    val secureNote = if (data != null && entity.type == CipherTypeEntity.SecureNote) {
        data.toSecureNoteEntity()
    } else entity.secureNote
    val card = if (data != null && entity.type == CipherTypeEntity.Card) {
        data.toCardEntity()
    } else entity.card
    val identity = if (data != null && entity.type == CipherTypeEntity.Identity) {
        data.toIdentityEntity()
    } else entity.identity
    val sshKey = if (data != null && entity.type == CipherTypeEntity.SshKey) {
        data.toSshKeyEntity()
    } else entity.sshKey

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
        archivedDate = entity.archivedDate,
        deletedDate = entity.deletedDate,
        keyBase64 = entity.key,
        // service fields
        service = service,
        // common
        name = if (data != null) data.name else entity.name,
        notes = if (data != null) data.notes else entity.notes,
        favorite = entity.favorite,
        encryptedFor = entity.encryptedFor,
        fields = fields
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
        login = login
            ?.run {
                BitwardenCipher.Login(
                    username = username,
                    password = password,
                    passwordRevisionDate = passwordRevisionDate,
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
        secureNote = secureNote
            ?.run {
                BitwardenCipher.SecureNote(
                    type = type.domain(),
                )
            },
        card = card
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
        identity = identity
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
        sshKey = sshKey
            ?.run {
                BitwardenCipher.SshKey(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    fingerprint = keyFingerprint,
                )
            },
        // other
        passwordHistory = passwordHistory
            .map {
                BitwardenCipher.Login.PasswordHistory(
                    password = it.password,
                    lastUsedDate = it.lastUsedDate,
                )
            },
    ).let {
        // Put remote into itself, since
        // we are creating it from remote
        // entity.
        it.copy(remoteEntity = it)
    }
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

private fun CipherDataEntity.toLoginEntity() = LoginEntity(
    uris = uris,
    username = username,
    password = password,
    passwordRevisionDate = passwordRevisionDate,
    totp = totp,
    fido2Credentials = fido2Credentials,
)

private fun CipherDataEntity.toSecureNoteEntity() = SecureNoteEntity(
    type = secureNoteType ?: SecureNoteTypeEntity.Generic,
)

private fun CipherDataEntity.toCardEntity() = CardEntity(
    cardholderName = cardholderName,
    brand = brand,
    number = number,
    expMonth = expMonth,
    expYear = expYear,
    code = code,
)

private fun CipherDataEntity.toIdentityEntity() = IdentityEntity(
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

private fun CipherDataEntity.toSshKeyEntity() = SshKeyEntity(
    privateKey = privateKey,
    publicKey = publicKey,
    keyFingerprint = keyFingerprint,
)
