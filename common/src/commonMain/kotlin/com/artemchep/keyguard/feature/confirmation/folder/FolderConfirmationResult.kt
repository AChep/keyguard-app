package com.artemchep.keyguard.feature.confirmation.folder

import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo

sealed interface FolderConfirmationResult {
    data object Deny : FolderConfirmationResult

    data class Confirm(
        val folderInfo: FolderInfo,
    ) : FolderConfirmationResult
}
