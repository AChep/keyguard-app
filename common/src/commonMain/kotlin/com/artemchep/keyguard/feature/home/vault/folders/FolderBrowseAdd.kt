package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER_STRING

internal fun FoldersRoute.Args.Parent.createAddFolderRequest(
    name: String,
): AddFolderRequestInfo = when (this) {
    is FoldersRoute.Args.Parent.Id -> AddFolderRequestInfo(
        accountId = accountId,
        name = name,
        parentId = folderId,
        hierarchyMode = FolderHierarchyMode.ParentId,
    )

    is FoldersRoute.Args.Parent.Path -> AddFolderRequestInfo(
        accountId = accountId,
        // In Path mode the path delimiter is structural, so the entered leaf
        // name must not introduce extra levels (e.g. "A/B" under "Work" should
        // not silently become "Work/A/B"). Strip the delimiter from the leaf.
        name = listOf(path, name.replace(FOLDER_HIERARCHY_DELIMITER.toString(), ""))
            .filter { it.isNotBlank() }
            .joinToString(separator = FOLDER_HIERARCHY_DELIMITER_STRING),
        parentId = null,
        hierarchyMode = FolderHierarchyMode.Path,
    )
}

internal data class AddFolderRequestInfo(
    val accountId: String,
    val name: String,
    val parentId: String?,
    val hierarchyMode: FolderHierarchyMode,
)
