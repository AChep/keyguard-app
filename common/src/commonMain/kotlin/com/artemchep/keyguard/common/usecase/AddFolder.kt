package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode

/**
 * Creates folders from the given requests.
 *
 * Requests are NOT deduplicated: each request maps 1:1 to exactly one created
 * folder. In particular, two requests with the same [AddFolderRequest.name] but
 * a different [AddFolderRequest.parentId] are intentionally treated as distinct
 * folders.
 */
interface AddFolder : (
    Collection<AddFolderRequest>,
) -> IO<Set<String>>

data class AddFolderRequest(
    val accountId: AccountId,
    val name: String,
    val parentId: String? = null,
    val hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
)
