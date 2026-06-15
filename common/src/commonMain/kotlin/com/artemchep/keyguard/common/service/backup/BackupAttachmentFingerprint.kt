package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.toHex

object BackupAttachmentFingerprint {
    fun remote(
        cipher: DSecret,
        attachment: DSecret.Attachment.Remote,
        cryptoGenerator: CryptoGenerator,
    ): String = create(
        accountId = cipher.accountId,
        localCipherId = cipher.id,
        remoteCipherId = attachment.remoteCipherId,
        attachmentId = attachment.id,
        size = attachment.size,
        cryptoGenerator = cryptoGenerator,
    )

    fun create(
        accountId: String,
        localCipherId: String,
        remoteCipherId: String?,
        attachmentId: String,
        size: Long?,
        cryptoGenerator: CryptoGenerator,
    ): String {
        val payload = createPayload(
            accountId = accountId,
            localCipherId = localCipherId,
            remoteCipherId = remoteCipherId,
            attachmentId = attachmentId,
            size = size,
        )
        return cryptoGenerator
            .hmacSha256(
                key = "keyguard-backup-attachment-fingerprint-v2".encodeToByteArray(),
                data = payload.encodeToByteArray(),
            )
            .toHex()
    }

    fun blobPath(
        blobId: String,
    ): String {
        val first = blobId.take(2).ifEmpty { "00" }
        val second = blobId.drop(2).take(2).ifEmpty { "00" }
        return "blobs/$first/$second/$blobId.zip"
    }
}

internal fun createPayload(
    accountId: String,
    localCipherId: String,
    remoteCipherId: String?,
    attachmentId: String,
    size: Long?,
): String {
    return buildString {
        append("v2\n")
        appendField("accountId", accountId)
        appendField("localCipherId", localCipherId)
        appendField("remoteCipherId", remoteCipherId)
        appendField("attachmentId", attachmentId)
        appendField("size", size?.toString())
    }
}

private fun StringBuilder.appendField(
    name: String,
    value: String?,
) {
    append(name)
    append(':')
    append(value?.length ?: -1)
    append(':')
    if (value != null) {
        append(value)
    }
    append('\n')
}
