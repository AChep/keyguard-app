package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.mapping.resolveTitle
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo

/**
 * The item side of a commit: the vault writes a plan resolves to once the target
 * account is known and its folders have been created.
 *
 * The review projects its rows through the same [resolveTitle],
 * because the review is a promise about what will be written and the two drifted
 * apart once already — the row read "Empty" while the item was created as "Untitled".
 */
internal fun ImportPlan.toCreateRequests(
    accountId: AccountId,
    folderIdByKey: Map<String, String>,
    untitledTitle: String,
): List<CreateRequest> = items.map { item ->
    val folderId = item.folderKey?.let(folderIdByKey::get)
    item.request.copy(
        title = item.request.resolveTitle(untitledTitle),
        ownership2 = CreateRequest.Ownership2(
            accountId = accountId.id,
            folder = folderId
                ?.let { FolderInfo.Id(it) }
                ?: FolderInfo.None,
            organizationId = null,
            collectionIds = emptySet(),
        ),
        // Written with the item rather than moved after it, so an item is
        // never left behind outside the archive or the trash.
        archivedDate = item.request.now.takeIf { item.archived },
        deletedDate = item.request.now.takeIf { item.trashed },
    )
}
