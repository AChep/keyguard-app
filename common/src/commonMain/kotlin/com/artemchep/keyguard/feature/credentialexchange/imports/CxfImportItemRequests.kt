package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo

/**
 * The item side of a commit: the vault writes a plan resolves to once the target
 * account is known and its folders have been created.
 *
 * The review projects its rows through the same [resolveTitle] (see [toUiItems]),
 * because the review is a promise about what will be written and the two drifted
 * apart once already — the row read "Empty" while the item was created as "Untitled".
 */
internal fun CxfImportPlan.toCreateRequests(
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
    )
}

/**
 * The title a planned item is written with: its own, or [untitledTitle] when the
 * source document left it blank.
 *
 * Blank rather than merely absent: a CXF title may legally be whitespace, and both
 * writing that verbatim and rendering it produce something that looks like a bug.
 */
internal fun CreateRequest.resolveTitle(
    untitledTitle: String,
): String = title
    ?.takeIf { it.isNotBlank() }
    ?: untitledTitle
