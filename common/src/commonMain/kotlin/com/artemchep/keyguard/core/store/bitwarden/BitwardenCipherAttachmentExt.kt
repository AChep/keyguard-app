package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile

internal data class PendingLocalAttachmentReconciliationResult(
    val cipher: BitwardenCipher,
    val obsoletePendingUploads: List<PendingUploadFile>,
    val replacementsByLocalId: Map<String, BitwardenCipher.Attachment.Remote>,
)

fun BitwardenCipher.pendingLocalAttachments() = attachments
    .filterIsInstance<BitwardenCipher.Attachment.Local>()
    .filter { it.pendingUpload != null }

fun BitwardenCipher.pendingRemoteAttachmentDeletionIds(): Set<String> {
    val baseRemoteIds = remoteEntity
        ?.attachments
        .orEmpty()
        .asSequence()
        .filterIsInstance<BitwardenCipher.Attachment.Remote>()
        .map { it.id }
        .toSet()
    if (baseRemoteIds.isEmpty()) {
        return emptySet()
    }

    val currentIds = attachments
        .asSequence()
        .map { it.id }
        .toSet()
    return baseRemoteIds - currentIds
}

fun BitwardenCipher.hasPendingAttachmentMutations(): Boolean =
    pendingLocalAttachments().isNotEmpty() ||
            pendingRemoteAttachmentDeletionIds().isNotEmpty()

internal fun findRemoteAttachmentReplacement(
    localAttachment: BitwardenCipher.Attachment.Local,
    remoteAttachments: List<BitwardenCipher.Attachment.Remote>,
    preferredRemoteAttachmentId: String? = null,
    excludedRemoteAttachmentIds: Set<String> = emptySet(),
): BitwardenCipher.Attachment.Remote? {
    preferredRemoteAttachmentId
        ?.let { remoteAttachmentId ->
            remoteAttachments.firstOrNull { attachment ->
                attachment.id == remoteAttachmentId &&
                        attachment.id !in excludedRemoteAttachmentIds
            }
        }
        ?.let { return it }

    val localKeyBase64 = localAttachment.keyBase64
        ?: return null
    return remoteAttachments.firstOrNull { attachment ->
        attachment.id !in excludedRemoteAttachmentIds &&
                attachment.keyBase64 == localKeyBase64
    }
}

internal fun BitwardenCipher.reconcilePendingLocalAttachments(
    remoteAttachments: List<BitwardenCipher.Attachment.Remote> = attachments
        .filterIsInstance<BitwardenCipher.Attachment.Remote>(),
    uploadedRemoteAttachmentIdsByLocalId: Map<String, String> = emptyMap(),
): PendingLocalAttachmentReconciliationResult {
    if (remoteAttachments.isEmpty()) {
        return PendingLocalAttachmentReconciliationResult(
            cipher = this,
            obsoletePendingUploads = emptyList(),
            replacementsByLocalId = emptyMap(),
        )
    }

    val obsoletePendingUploads = mutableListOf<PendingUploadFile>()
    val replacementsByLocalId = linkedMapOf<String, BitwardenCipher.Attachment.Remote>()
    val usedRemoteAttachmentIds = mutableSetOf<String>()
    attachments.forEach { attachment ->
        val localAttachment = attachment as? BitwardenCipher.Attachment.Local
            ?: return@forEach
        val pendingUpload = localAttachment.pendingUpload
            ?: return@forEach
        val preferredRemoteAttachmentId = uploadedRemoteAttachmentIdsByLocalId[localAttachment.id]
        if (pendingUpload.remoteId != null && preferredRemoteAttachmentId == null) {
            return@forEach
        }
        val replacement = findRemoteAttachmentReplacement(
            localAttachment = localAttachment,
            remoteAttachments = remoteAttachments,
            preferredRemoteAttachmentId = preferredRemoteAttachmentId,
            excludedRemoteAttachmentIds = usedRemoteAttachmentIds,
        ) ?: return@forEach

        usedRemoteAttachmentIds += replacement.id
        obsoletePendingUploads += pendingUpload
        replacementsByLocalId[localAttachment.id] = replacement
    }

    if (replacementsByLocalId.isEmpty()) {
        return PendingLocalAttachmentReconciliationResult(
            cipher = this,
            obsoletePendingUploads = emptyList(),
            replacementsByLocalId = emptyMap(),
        )
    }

    val reconciledAttachments = attachments.mapNotNull { attachment ->
        when (attachment) {
            is BitwardenCipher.Attachment.Local -> {
                replacementsByLocalId[attachment.id]
                    ?: attachment
            }

            is BitwardenCipher.Attachment.Remote -> {
                attachment.takeUnless {
                    attachment.id in usedRemoteAttachmentIds
                }
            }
        }
    }

    return PendingLocalAttachmentReconciliationResult(
        cipher = copy(
            attachments = reconciledAttachments,
        ),
        obsoletePendingUploads = obsoletePendingUploads,
        replacementsByLocalId = replacementsByLocalId,
    )
}
