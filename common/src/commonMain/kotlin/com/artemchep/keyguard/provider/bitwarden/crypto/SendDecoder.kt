package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.entity.domain

fun BitwardenSend.Companion.encrypted(
    accountId: String,
    sendId: String,
    entity: SendEntity,
) = kotlin.run {
    val service = BitwardenService(
        remote = BitwardenService.Remote(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = null, // can not be trashed
        ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
    BitwardenSend(
        accountId = accountId,
        sendId = sendId,
        accessId = entity.accessId,
        revisionDate = entity.revisionDate,
        createdDate = entity.creationDate,
        deletedDate = entity.deletionDate,
        expirationDate = entity.expirationDate,
        keyBase64 = entity.key,
        // service fields
        service = service,
        // common
        authType = entity.authType?.domain(),
        name = entity.name,
        notes = entity.notes,
        accessCount = entity.accessCount,
        maxAccessCount = entity.maxAccessCount,
        password = entity.password,
        disabled = entity.disabled,
        hideEmail = entity.hideEmail,
        emails = entity.emails
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty(),
        // types
        type = entity.type.domain(),
        file = entity.file
            ?.let { file ->
                BitwardenSend.File(
                    id = file.id
                        ?: file.fileName.orEmpty(),
                    fileName = file.fileName.orEmpty(),
                    keyBase64 = file.key,
                    size = file.size?.toLongOrNull(),
                    sizeName = file.sizeName,
                )
            },
        text = entity.text
            ?.let { text ->
                BitwardenSend.Text(
                    text = text.text,
                    hidden = text.hidden,
                )
            },
    )
}
