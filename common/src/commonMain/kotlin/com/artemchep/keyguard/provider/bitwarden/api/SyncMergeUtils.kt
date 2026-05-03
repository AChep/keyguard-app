package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.pendingRemoteAttachmentDeletionIds

suspend fun merge(
    remote: BitwardenCipher,
    local: BitwardenCipher?,
    getPasswordStrength: GetPasswordStrength,
): BitwardenCipher {
    val attachments = mergeAttachments(
        remote = remote,
        local = local,
    )

    var login = remote.login
    // Calculate or copy over the password strength of
    // the password.
    if (remote.login != null) run {
        val password = remote.login.password
            ?: return@run
        val strength = local?.login?.passwordStrength
            .takeIf { local?.login?.password == remote.login.password }
        // Generate a password strength badge.
            ?: getPasswordStrength(password)
                .attempt()
                .bind()
                .getOrNull()
                ?.let { ps ->
                    BitwardenCipher.Login.PasswordStrength(
                        password = password,
                        crackTimeSeconds = ps.crackTimeSeconds,
                        version = ps.version,
                    )
                }
        login = login?.copy(
            passwordStrength = strength,
        )
    }

    val ignoredAlerts = local?.ignoredAlerts.orEmpty()
    return remote.copy(
        login = login,
        attachments = attachments,
        ignoredAlerts = ignoredAlerts,
    )
}

private fun mergeAttachments(
    remote: BitwardenCipher,
    local: BitwardenCipher?,
): List<BitwardenCipher.Attachment> {
    val removedRemoteAttachmentIds = local
        ?.pendingRemoteAttachmentDeletionIds()
        .orEmpty()
    val remoteAttachmentsById = remote.attachments
        .asSequence()
        .filterNot { it.id in removedRemoteAttachmentIds }
        .associateByTo(LinkedHashMap()) { it.id }
    val baseRemoteAttachmentsById = local
        ?.remoteEntity
        ?.attachments
        .orEmpty()
        .asSequence()
        .filterIsInstance<BitwardenCipher.Attachment.Remote>()
        .associateBy { it.id }
    if (local == null) {
        return remoteAttachmentsById.values.toList()
    }

    val orderedAttachments = mutableListOf<BitwardenCipher.Attachment>()
    local.attachments.forEach { localAttachment ->
        when (localAttachment) {
            is BitwardenCipher.Attachment.Remote -> {
                val remoteAttachment = remoteAttachmentsById
                    .remove(localAttachment.id) as? BitwardenCipher.Attachment.Remote
                    ?: return@forEach
                val baseAttachment = baseRemoteAttachmentsById[localAttachment.id]
                val hasLocalRename = baseAttachment != null &&
                        localAttachment.fileName != baseAttachment.fileName
                orderedAttachments += if (hasLocalRename) {
                    remoteAttachment.copy(
                        fileName = localAttachment.fileName,
                    )
                } else {
                    remoteAttachment
                }
            }

            is BitwardenCipher.Attachment.Local -> {
                orderedAttachments += localAttachment
            }
        }
    }
    orderedAttachments += remoteAttachmentsById.values
    return orderedAttachments
}

suspend fun merge(
    remote: BitwardenProfile,
    local: BitwardenProfile?,
): BitwardenProfile {
    val hidden = local?.hidden == true
    return remote.copy(
        hidden = hidden,
    )
}
