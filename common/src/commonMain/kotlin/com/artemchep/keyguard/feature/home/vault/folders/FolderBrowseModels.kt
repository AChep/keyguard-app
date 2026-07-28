package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.util.FolderHierarchyKey

internal data class FolderBrowseTree(
    val title: String?,
    val current: FolderBrowseNode?,
    val missing: Boolean,
    val items: List<FolderBrowseNode>,
)

internal data class FolderBrowseNode(
    val key: String,
    val name: String,
    val anchor: FoldersRoute.Args.Parent,
    val hierarchyKey: FolderHierarchyKey,
    val hierarchyParentKey: FolderHierarchyKey?,
    val folder: DFolder,
    val descendantFolders: List<DFolder>,
    /**
     * The number of direct child folders that are currently visible under this
     * node (i.e. that have at least one visible descendant).
     */
    val visibleChildFolderCount: Int,
    val hasVisibleChildren: Boolean,
    val depth: Int,
    val pathParentPath: String? = null,
) {
    val directFolders: List<DFolder> = listOf(folder)
    val directFolderIds: Set<String> = setOf(folder.id)
    val descendantFolderIds: Set<String> = descendantFolders
        .mapTo(mutableSetOf()) { it.id }
    val deleted: Boolean = descendantFolders.isNotEmpty() &&
            descendantFolders.all { it.deleted }
}
