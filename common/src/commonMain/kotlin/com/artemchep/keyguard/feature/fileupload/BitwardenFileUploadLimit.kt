package com.artemchep.keyguard.feature.fileupload

import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType

internal const val BITWARDEN_FILE_UPLOAD_MAX_BYTES: Long = 500L * 1024L * 1024L
internal const val KEEPASS_FILE_UPLOAD_MAX_BYTES: Long = 1L * 1024L * 1024L

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
