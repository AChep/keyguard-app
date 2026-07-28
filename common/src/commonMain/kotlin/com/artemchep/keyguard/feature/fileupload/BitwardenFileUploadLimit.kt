package com.artemchep.keyguard.feature.fileupload

import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType

internal const val BITWARDEN_FILE_UPLOAD_MAX_BYTES: Long = 500L * 1024L * 1024L

// KDBX binary field sizes are signed 32-bit values, allowing roughly 2 GiB per attachment.
// We cap attachments at 500 MiB purely to avoid degrading sync performance too much.
internal const val KEEPASS_FILE_UPLOAD_MAX_BYTES: Long = 500L * 1024L * 1024L

internal fun isBitwardenUploadFileSizeAllowed(
    size: Long?,
): Boolean = size == null || size <= BITWARDEN_FILE_UPLOAD_MAX_BYTES

internal fun isKeePassUploadFileSizeAllowed(
    size: Long?,
): Boolean = size == null || size <= KEEPASS_FILE_UPLOAD_MAX_BYTES

internal fun isVaultAttachmentFileSizeAllowed(
    size: Long?,
    accountType: AccountType?,
): Boolean = when (accountType) {
    AccountType.KEEPASS -> isKeePassUploadFileSizeAllowed(size)
    AccountType.BITWARDEN,
    null,
    -> isBitwardenUploadFileSizeAllowed(size)
}
