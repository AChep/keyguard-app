package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.fileupload.isVaultAttachmentFileSizeAllowed
import com.artemchep.keyguard.feature.fileupload.toAttachmentFileMetadata
import com.artemchep.keyguard.feature.fileupload.vaultAttachmentFileSizeLimit
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import kotlin.uuid.Uuid

internal sealed interface VaultAttachmentSelectionResult {
    data class Success(
        val attachment: SkeletonAttachment.Local,
    ) : VaultAttachmentSelectionResult

    data class FileTooLarge(
        val maximumBytes: Long,
    ) : VaultAttachmentSelectionResult
}

internal fun FilePickerResult.toVaultAttachmentSelectionResult(
    accountType: AccountType?,
): VaultAttachmentSelectionResult {
    val allowed = isVaultAttachmentFileSizeAllowed(size, accountType)
    if (!allowed) {
        return VaultAttachmentSelectionResult.FileTooLarge(
            maximumBytes = vaultAttachmentFileSizeLimit(accountType),
        )
    }

    return VaultAttachmentSelectionResult.Success(
        attachment = toSkeletonAttachment(),
    )
}

private fun FilePickerResult.toSkeletonAttachment(): SkeletonAttachment.Local {
    val metadata = toAttachmentFileMetadata(
        fallbackName = "File",
    )
    return SkeletonAttachment.Local(
        identity = SkeletonAttachment.Local.Identity(
            id = Uuid.random().toString(),
            uri = metadata.uri,
            size = metadata.size,
        ),
        name = metadata.name,
        size = metadata.size?.let(::humanReadableByteCountSI).orEmpty(),
    )
}

internal suspend fun handleVaultAttachmentSelection(
    result: FilePickerResult,
    accountType: AccountType?,
    uploadLimitErrorTitle: suspend (Long) -> String,
    showMessage: (ToastMessage) -> Unit,
    addAttachment: (SkeletonAttachment.Local) -> Unit,
) {
    val selection = result
        .toVaultAttachmentSelectionResult(accountType)
    when (selection) {
        is VaultAttachmentSelectionResult.Success -> {
            addAttachment(selection.attachment)
        }
        is VaultAttachmentSelectionResult.FileTooLarge -> {
            val msg = ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = uploadLimitErrorTitle(selection.maximumBytes),
            )
            showMessage(msg)
        }
    }
}
