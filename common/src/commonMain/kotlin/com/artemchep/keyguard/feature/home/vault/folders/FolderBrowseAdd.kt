package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER_STRING

internal fun createRootAddFolderRequest(
    accountId: String,
    name: String,
    hierarchyMode: FolderHierarchyMode,
): AddFolderRequestInfo = AddFolderRequestInfo(
    accountId = accountId,
    name = name,
    parentId = null,
    hierarchyMode = hierarchyMode,
)

internal fun DFolder.createAddFolderRequest(
    name: String,
): AddFolderRequestInfo = when (hierarchyMode) {
    FolderHierarchyMode.ParentId -> AddFolderRequestInfo(
        accountId = accountId,
        name = name,
        parentId = id,
        hierarchyMode = FolderHierarchyMode.ParentId,
    )

    FolderHierarchyMode.Path -> AddFolderRequestInfo(
        accountId = accountId,
        // In Path mode the path delimiter is structural, so the entered leaf
        // name must not introduce extra levels (e.g. "A/B" under "Work" should
        // not silently become "Work/A/B"). Strip the delimiter from the leaf.
        name = listOf(this.name, name.replace(FOLDER_HIERARCHY_DELIMITER.toString(), ""))
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
