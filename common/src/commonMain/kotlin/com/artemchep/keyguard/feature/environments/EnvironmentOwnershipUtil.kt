package com.artemchep.keyguard.feature.environments

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.environments.ui.EnvironmentEditor

fun EnvironmentFolder.toFolderInfo(): FolderInfo = when (this) {
    is EnvironmentFolder.None -> FolderInfo.None
    is EnvironmentFolder.Id -> FolderInfo.Id(id)
    is EnvironmentFolder.New -> FolderInfo.New(name)
}

fun FolderInfo.toEnvironmentFolder(): EnvironmentFolder = when (this) {
    is FolderInfo.None -> EnvironmentFolder.None
    is FolderInfo.Id -> EnvironmentFolder.Id(id)
    is FolderInfo.New -> EnvironmentFolder.New(name)
}

fun EnvironmentEditorState.toOwnership2() = CreateRequest.Ownership2(
    accountId = accountId.takeIf { it.isNotBlank() },
    folder = ownership.folder.toFolderInfo(),
    organizationId = ownership.organizationId,
    collectionIds = ownership.collectionIds,
)

/** Applies the choice of the "Save to" dialog to the draft. */
fun EnvironmentEditor.setOwnership(
    result: OrganizationConfirmationResult.Confirm,
) {
    onOwnershipChange?.invoke(
        result.accountId,
        EnvironmentOwnership(
            folder = result.folderId.toEnvironmentFolder(),
            organizationId = result.organizationId,
            collectionIds = result.collectionsIds,
        ),
    )
}
