package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.SyncSends
import com.artemchep.keyguard.provider.bitwarden.entity.domain

fun BitwardenSend.Companion.encrypted(
    accountId: String,
    sendId: String,
    entity: SyncSends,
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
        deletedDate = entity.deletionDate,
        expirationDate = entity.expirationDate,
        keyBase64 = entity.key,
        // service fields
        service = service,
        // common
        name = entity.name,
        notes = entity.notes,
        accessCount = entity.accessCount,
        maxAccessCount = entity.maxAccessCount,
        password = entity.password,
        disabled = entity.disabled,
        hideEmail = entity.hideEmail,
        // types
        type = entity.type.domain(),
        file = entity.file
            ?.let { file ->
                BitwardenSend.File(
                    id = file.fileName,
                    fileName = file.fileName,
                    keyBase64 = file.key,
                    size = file.size.toLong(),
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
