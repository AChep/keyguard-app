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
    val currentNode = parent?.let { anchor ->
        index.nodeOf(
            accountId = anchor.accountId,
            folderId = anchor.folderId,
        )
    }
    val current = currentNode
        ?.toFolderBrowseNode(
            index = index,
            visibleFolderIds = visibleFolderIds,
        )
    val children = when {
        parent == null -> index.childrenOf(null)
        currentNode != null -> index.childrenOf(currentNode.key)
        else -> emptyList()
    }
    val items = children
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
        title = current?.name,
        current = current,
        missing = parent != null && current == null,
        items = items,
    )
}

private fun FolderHierarchyIndexNode<DFolder>.toFolderBrowseNode(
    index: FolderHierarchyIndex<DFolder>,
    visibleFolderIds: Set<String>,
): FolderBrowseNode {
    val folder = item
    val descendantFolders = index.descendantsOf(key)
        .distinctBy { it.id }
        .sortedWith(folderComparator)
    // Count the direct child nodes the user would actually see if they drilled
    // into this node. The same count drives both the badge and the chevron.
    val visibleChildFolderCount = index.childrenOf(key)
        .count { child ->
            child.hasVisibleDescendant(
                index = index,
                visibleFolderIds = visibleFolderIds,
            )
        }
    return FolderBrowseNode(
        key = folder.browseKey,
        name = name,
        anchor = FoldersRoute.Args.Parent(
            accountId = folder.accountId,
            folderId = folder.id,
        ),
        hierarchyKey = key,
        hierarchyParentKey = parentKey,
        folder = folder,
        descendantFolders = descendantFolders,
        visibleChildFolderCount = visibleChildFolderCount,
        hasVisibleChildren = visibleChildFolderCount > 0,
        depth = depth,
        pathParentPath = (parentKey as? FolderHierarchyKey.Path)?.path,
    )
}

private fun FolderHierarchyIndexNode<DFolder>.hasVisibleDescendant(
    index: FolderHierarchyIndex<DFolder>,
    visibleFolderIds: Set<String>,
): Boolean = index.anyDescendant(key) { folder ->
    folder.id in visibleFolderIds
}

private val DFolder.browseKey: String
    get() = "${accountId.length}:$accountId|${id.length}:$id"

private val folderBrowseNodeComparator: Comparator<FolderBrowseNode> =
    StringComparatorIgnoreCase<FolderBrowseNode> { it.name }
        // Tie-break same-named nodes by their account, locale-consistently, so the
        // order is stable without depending on the opaque composite key.
        .thenBy(StringComparatorIgnoreCase<String> { it }) { it.anchor.accountId }
        .thenBy { it.anchor.folderId }

private val folderComparator: Comparator<DFolder> =
    StringComparatorIgnoreCase { it.name }
