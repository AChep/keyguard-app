package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.util.FolderHierarchyIndex
import com.artemchep.keyguard.common.util.FolderHierarchyIndexNode
import com.artemchep.keyguard.common.util.FolderHierarchyKey
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.createFolderHierarchyIndex

internal fun buildFolderBrowseTree(
    folders: List<DFolder>,
    visibleFolderIds: Set<String>,
    parent: FoldersRoute.Args.Parent?,
): FolderBrowseTree {
    val index = createFolderHierarchyIndex(
        folders = folders,
        accountId = { it.accountId },
        lens = { it.name },
        id = { it.id },
        parentId = { it.parentId },
        hierarchyMode = { it.hierarchyMode },
    )
    val parentKey = parent?.toFolderHierarchyKey()
    val items = index.childrenOf(parentKey)
        .asSequence()
        .filter { node ->
            node.hasVisibleDescendant(
                index = index,
                visibleFolderIds = visibleFolderIds,
            )
        }
        .map { node ->
            node.toFolderBrowseNode(
                index = index,
                visibleFolderIds = visibleFolderIds,
            )
        }
        .sortedWith(folderBrowseNodeComparator)
        .toList()
    return FolderBrowseTree(
        title = parentKey
            ?.let(index::node)
            ?.name,
        items = items,
    )
}

private fun FolderHierarchyIndexNode<DFolder>.toFolderBrowseNode(
    index: FolderHierarchyIndex<DFolder>,
    visibleFolderIds: Set<String>,
): FolderBrowseNode {
    val descendantFolders = index.descendantsOf(key)
        .distinctBy { it.id }
        .sortedWith(folderComparator)
    // The direct child nodes the user would actually see if they drilled into
    // this node. Reused both for the chevron affordance and the count badge so
    // the visible-descendant test runs only once per child.
    val visibleChildren = index.childrenOf(key)
        .filter { child ->
            child.hasVisibleDescendant(
                index = index,
                visibleFolderIds = visibleFolderIds,
            )
        }
    return FolderBrowseNode(
        key = key.browseKey,
        name = name,
        anchor = key.toRouteParent(),
        directFolders = directItems
            .distinctBy { it.id }
            .sortedWith(folderComparator),
        descendantFolders = descendantFolders,
        visibleChildFolderCount = visibleChildren.size,
        hasVisibleChildren = visibleChildren.isNotEmpty(),
        depth = depth,
        pathParentPath = (parentKey as? FolderHierarchyKey.Path)?.path,
    )
}

private fun FolderHierarchyIndexNode<DFolder>.hasVisibleDescendant(
    index: FolderHierarchyIndex<DFolder>,
    visibleFolderIds: Set<String>,
): Boolean = index.descendantsOf(key)
    .any { it.id in visibleFolderIds }

private fun FoldersRoute.Args.Parent.toFolderHierarchyKey(): FolderHierarchyKey = when (this) {
    is FoldersRoute.Args.Parent.Id -> FolderHierarchyKey.Id(
        accountId = accountId,
        folderId = folderId,
    )

    is FoldersRoute.Args.Parent.Path -> FolderHierarchyKey.Path(
        accountId = accountId,
        path = path,
    )
}

private fun FolderHierarchyKey.toRouteParent(): FoldersRoute.Args.Parent = when (this) {
    is FolderHierarchyKey.Id -> FoldersRoute.Args.Parent.Id(
        accountId = accountId,
        folderId = folderId,
    )

    is FolderHierarchyKey.Path -> FoldersRoute.Args.Parent.Path(
        accountId = accountId,
        path = path,
    )
}

private val FolderHierarchyKey.browseKey: String
    get() = when (this) {
        is FolderHierarchyKey.Id -> "id|$accountId|$folderId"
        is FolderHierarchyKey.Path -> "path|$accountId|$path"
    }

private val folderBrowseNodeComparator: Comparator<FolderBrowseNode> =
    StringComparatorIgnoreCase<FolderBrowseNode> { it.name }
        // Tie-break same-named nodes by their account, locale-consistently, so the
        // order is stable without depending on the opaque composite key.
        .thenBy(StringComparatorIgnoreCase<String> { it }) { it.anchor.accountId }

private val folderComparator: Comparator<DFolder> =
    StringComparatorIgnoreCase { it.name }
