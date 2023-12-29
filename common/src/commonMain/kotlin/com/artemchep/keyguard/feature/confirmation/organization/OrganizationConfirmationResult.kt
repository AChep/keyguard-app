package com.artemchep.keyguard.feature.confirmation.organization

sealed interface OrganizationConfirmationResult {
    data object Deny : OrganizationConfirmationResult

    data class Confirm(
        val accountId: String,
        val organizationId: String?,
        val collectionsIds: Set<String>,
        val folderId: FolderInfo,
    ) : OrganizationConfirmationResult
}
