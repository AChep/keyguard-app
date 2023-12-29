package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend

suspend fun BitwardenSend.toDomain(
): DSend {
    val type: DSend.Type = when (type) {
        BitwardenSend.Type.None -> DSend.Type.None
        BitwardenSend.Type.Text -> DSend.Type.Text
        BitwardenSend.Type.File -> DSend.Type.File
    }
    return DSend(
        id = sendId,
        accountId = accountId,
        accessId = accessId,
        keyBase64 = keyBase64,
        revisionDate = revisionDate,
        createdDate = createdDate,
        deletedDate = deletedDate,
        expirationDate = expirationDate,
        service = service,
        // common
        name = name.orEmpty(),
        notes = notes.orEmpty(),
        accessCount = accessCount,
        maxAccessCount = maxAccessCount,
        disabled = disabled,
        hideEmail = hideEmail ?: false,
        password = password,
        // types
        type = type,
        text = text?.toDomain(),
        file = file?.toDomain(),
    )
}

fun BitwardenSend.Text.toDomain() = DSend.Text(
    text = text,
    hidden = hidden,
)

fun BitwardenSend.File.toDomain() = DSend.File(
    id = id,
    fileName = fileName,
    keyBase64 = keyBase64,
    size = size,
)
