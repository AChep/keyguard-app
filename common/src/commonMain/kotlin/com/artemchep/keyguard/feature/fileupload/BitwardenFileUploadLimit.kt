package com.artemchep.keyguard.feature.fileupload

import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType

internal const val BITWARDEN_FILE_UPLOAD_MAX_BYTES: Long = 500L * 1024L * 1024L

internal fun isBitwardenUploadFileSizeAllowed(
    size: Long?,
): Boolean = size == null || size <= BITWARDEN_FILE_UPLOAD_MAX_BYTES

internal fun isKeePassUploadFileSizeAllowed(
    size: Long?,
): Boolean = size == null || size <= KEEPASS_FILE_UPLOAD_MAX_BYTES

internal fun vaultAttachmentFileSizeLimit(
    accountType: AccountType?,
): Long = when (accountType) {
    AccountType.KEEPASS -> KEEPASS_FILE_UPLOAD_MAX_BYTES

    AccountType.BITWARDEN,
    null,
    -> BITWARDEN_FILE_UPLOAD_MAX_BYTES
}

internal fun isVaultAttachmentFileSizeAllowed(
    size: Long?,
    accountType: AccountType?,
): Boolean = size == null || size <= vaultAttachmentFileSizeLimit(accountType)
