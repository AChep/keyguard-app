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
    val authType = authTypeOrInferred
        .toDomain()
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
        authType = authType,
        name = name.orEmpty(),
        notes = notes.orEmpty(),
        accessCount = accessCount,
        maxAccessCount = maxAccessCount,
        hasPassword = authType == DSend.AuthType.Password,
        synced = !service.deleted &&
                revisionDate == service.remote?.revisionDate,
        disabled = disabled,
        hideEmail = hideEmail ?: false,
        emails = emails
            .takeIf { authType == DSend.AuthType.Email }
            .orEmpty(),
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
    sizeName = sizeName,
)

private fun BitwardenSend.AuthType.toDomain() = when (this) {
    BitwardenSend.AuthType.Email -> DSend.AuthType.Email
    BitwardenSend.AuthType.Password -> DSend.AuthType.Password
    BitwardenSend.AuthType.None -> DSend.AuthType.None
}
