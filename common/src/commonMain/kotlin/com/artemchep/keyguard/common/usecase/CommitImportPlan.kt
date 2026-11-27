package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSource
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo

/**
 * Writes an import plan into an account: its folders, then its items with
 * their attachments, archived or trashed as the source had them.
 */
interface CommitImportPlan : (
    CommitImportPlanRequest,
) -> IO<CommitImportPlanResult>

data class CommitImportPlanRequest(
    val plan: ImportPlan,
    val accountId: AccountId,
    /**
     * The title an item with a blank one is written with: the very string
     * the review showed for it.
     */
    val untitledTitle: String,
    /**
     * Where the attachments of the plan are read from. Required if any of
     * its items has one.
     */
    val attachments: ImportAttachmentSource?,
    /**
     * The folder that the items without a planned folder go into: none, a
     * new one or an existing one of the account.
     */
    val folder: FolderInfo = FolderInfo.None,
)

data class CommitImportPlanResult(
    val itemCount: Int,
    val folderCount: Int,
)
