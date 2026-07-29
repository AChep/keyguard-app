package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfLinkedItem
import com.artemchep.keyguard.common.util.FolderHierarchyIndex
import com.artemchep.keyguard.common.util.FolderHierarchyIndexNode
import com.artemchep.keyguard.common.util.FolderHierarchyKey
import com.artemchep.keyguard.common.util.createFolderHierarchyIndex

/**
 * Maps [folders] into a tree of CXF collections. [itemsByFolderId] maps a
 * folder id to the base64url ids of the exported items it contains; those are
 * wired in as [CxfLinkedItem]s.
 *
 * Emission is per hierarchy *node*, not per folder row, so the document shows
 * exactly what Keyguard's own folder browser shows: several path folders
 * sharing one path are one collection holding all of their items. Nesting
 * follows each folder's own hierarchy mode via the shared folder-hierarchy
 * engine, which guarantees the node graph is a forest.
 */
internal fun buildCollections(
    folders: List<DFolder>,
    itemsByFolderId: Map<String, List<String>>,
): List<CxfCollection> {
    if (folders.isEmpty()) {
        return emptyList()
    }
    val index = createFolderHierarchyIndex(
        folders = folders,
        accountId = { it.accountId },
        lens = { it.name },
        id = { it.id },
        parentId = { it.parentId },
        hierarchyMode = { it.hierarchyMode },
    )
    // Which nodes already became a collection, so every folder of the account
    // lands in exactly one of them.
    val emitted = mutableSetOf<FolderHierarchyKey>()
    val result = mutableListOf<CxfCollection>()
    // `pending` starts as the roots and also receives subtrees re-rooted at the
    // depth cap; `unswept` is the backstop for a node the walk never reached.
    // Both must drain in one loop, or a node re-rooted during the sweep would
    // land on a queue nobody reads again.
    val pending = ArrayDeque(index.childrenOf(null))
    val unswept = ArrayDeque(index.nodes)
    while (pending.isNotEmpty() || unswept.isNotEmpty()) {
        val node = pending.removeFirstOrNull()
            ?: unswept.removeFirst()
        if (node.key in emitted) {
            continue
        }
        result += node.toCollection(
            index = index,
            itemsByFolderId = itemsByFolderId,
            emitted = emitted,
            depth = 1,
            reRoot = pending::addLast,
        )
    }
    return result
}

private fun FolderHierarchyIndexNode<DFolder>.toCollection(
    index: FolderHierarchyIndex<DFolder>,
    itemsByFolderId: Map<String, List<String>>,
    emitted: MutableSet<FolderHierarchyKey>,
    depth: Int,
    reRoot: (FolderHierarchyIndexNode<DFolder>) -> Unit,
): CxfCollection {
    emitted += key
    val subCollections = childCollections(
        index = index,
        itemsByFolderId = itemsByFolderId,
        emitted = emitted,
        depth = depth,
        reRoot = reRoot,
    )
    val folder = item
    val items = itemsByFolderId[folder.id]
        .orEmpty()
        .map { itemId -> CxfLinkedItem(item = itemId) }
    return CxfCollection(
        // Every folder row is its own node, so the collection is identified by
        // that row -- two same-named rows stay two collections, as the folder
        // browser shows them.
        id = encodeIdToB64Url(folder.id),
        modifiedAt = folder.revisionDate.epochSeconds,
        // A root keeps its whole path as the title, so a subtree re-rooted at
        // the depth cap loses the parent edge but not the prefix.
        title = if (depth == 1) {
            (key as? FolderHierarchyKey.Path)?.path ?: name
        } else {
            name
        },
        items = items,
        subCollections = subCollections.takeIf { it.isNotEmpty() },
    )
}

/**
 * The nested collections of this node, or an empty list once
 * [CXF_MAX_COLLECTION_DEPTH] is reached -- in which case every child is handed
 * to [reRoot] to be emitted as a new top-level collection instead of dropped.
 */
private fun FolderHierarchyIndexNode<DFolder>.childCollections(
    index: FolderHierarchyIndex<DFolder>,
    itemsByFolderId: Map<String, List<String>>,
    emitted: MutableSet<FolderHierarchyKey>,
    depth: Int,
    reRoot: (FolderHierarchyIndexNode<DFolder>) -> Unit,
): List<CxfCollection> {
    val children = index.childrenOf(key)
    if (depth >= CXF_MAX_COLLECTION_DEPTH) {
        children
            .filter { it.key !in emitted }
            .forEach(reRoot)
        return emptyList()
    }
    return children.mapNotNull { child ->
        child
            .takeIf { it.key !in emitted }
            ?.toCollection(
                index = index,
                itemsByFolderId = itemsByFolderId,
                emitted = emitted,
                depth = depth + 1,
                reRoot = reRoot,
            )
    }
}
