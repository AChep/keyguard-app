package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder

internal data class FolderBrowseTree(
    val title: String?,
    val items: List<FolderBrowseNode>,
)

internal data class FolderBrowseNode(
    val key: String,
    val name: String,
    val anchor: FoldersRoute.Args.Parent,
    val directFolders: List<DFolder>,
    val descendantFolders: List<DFolder>,
    /**
     * The number of direct child folders that are currently visible under this
     * node (i.e. that have at least one visible descendant). Used by the count
     * badge; unlike [directFolders] this reflects the real sub-folder count
     * rather than the folders collapsed into this node.
     */
    val visibleChildFolderCount: Int,
    val hasVisibleChildren: Boolean,
    val depth: Int,
    val pathParentPath: String? = null,
) {
    val directFolderIds: Set<String> = directFolders
        .mapTo(mutableSetOf()) { it.id }
    val descendantFolderIds: Set<String> = descendantFolders
        .mapTo(mutableSetOf()) { it.id }
    val deleted: Boolean = descendantFolders.isNotEmpty() &&
            descendantFolders.all { it.deleted }
}
