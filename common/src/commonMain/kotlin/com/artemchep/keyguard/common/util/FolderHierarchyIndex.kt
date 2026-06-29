package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.model.FolderHierarchyMode

sealed interface FolderHierarchyKey {
    val accountId: String

    data class Path(
        override val accountId: String,
        val path: String,
    ) : FolderHierarchyKey

    data class Id(
        override val accountId: String,
        val folderId: String,
    ) : FolderHierarchyKey
}

data class FolderHierarchyIndexNode<T : Any>(
    val key: FolderHierarchyKey,
    val parentKey: FolderHierarchyKey?,
    val name: String,
    val depth: Int,
    val directItems: List<T>,
)

/**
 * A flattened, queryable view of one or more folder trees.
 *
 * Invariants (established by [createFolderHierarchyIndex]):
 * - Nodes are scoped per account; keys carry their `accountId` and never cross
 *   account boundaries.
 * - In [FolderHierarchyMode.Path], folders sharing the same path collapse into a
 *   single node whose [FolderHierarchyIndexNode.directItems] holds every folder.
 * - A folder whose parent is missing (dangling parent) is re-rooted: its
 *   `parentKey` is `null`.
 * - Parent-id cycles (a self-parent, or `A -> B -> A`) are broken by re-rooting
 *   the offending folder, so every node is reachable from `childrenOf(null)`.
 */
class FolderHierarchyIndex<T : Any> internal constructor(
    private val nodesByKey: Map<FolderHierarchyKey, FolderHierarchyIndexNode<T>>,
    private val childKeysByParentKey: Map<FolderHierarchyKey?, List<FolderHierarchyKey>>,
) {
    // Precomputed once: for each key, the node's own [directItems] followed by
    // every descendant's items (each folder once). Avoids re-running a DFS per
    // [descendantsOf] call.
    private val descendantsByKey: Map<FolderHierarchyKey, List<T>> =
        computeDescendantsByKey()

    fun node(
        key: FolderHierarchyKey,
    ): FolderHierarchyIndexNode<T>? = nodesByKey[key]

    fun childrenOf(
        parentKey: FolderHierarchyKey?,
    ): List<FolderHierarchyIndexNode<T>> = childKeysByParentKey[parentKey]
        .orEmpty()
        .mapNotNull(nodesByKey::get)

    fun descendantsOf(
        key: FolderHierarchyKey,
    ): List<T> = descendantsByKey[key].orEmpty()

    private fun computeDescendantsByKey(): Map<FolderHierarchyKey, List<T>> {
        val result = mutableMapOf<FolderHierarchyKey, List<T>>()
        for (rootKey in nodesByKey.keys) {
            if (rootKey in result) {
                continue
            }
            collectDescendantsInto(rootKey, result)
        }
        return result
    }

    // Iterative post-order traversal: a node is finalized only after all of its
    // children, so every entry can be assembled from already-finalized children.
    // The on-stack set guards against parent-id cycles.
    private fun collectDescendantsInto(
        startKey: FolderHierarchyKey,
        result: MutableMap<FolderHierarchyKey, List<T>>,
    ) {
        val stack = ArrayDeque<FolderHierarchyKey>()
        val onStack = mutableSetOf<FolderHierarchyKey>()
        stack.addLast(startKey)
        onStack += startKey
        while (stack.isNotEmpty()) {
            val currentKey = stack.last()
            val node = nodesByKey[currentKey]
            if (node == null) {
                stack.removeLast()
                onStack -= currentKey
                result[currentKey] = emptyList()
                continue
            }
            val children = childKeysByParentKey[currentKey].orEmpty()
            val pending = children.firstOrNull { childKey ->
                childKey != currentKey &&
                        childKey !in result &&
                        childKey !in onStack
            }
            if (pending != null) {
                stack.addLast(pending)
                onStack += pending
                continue
            }
            val items = buildList {
                addAll(node.directItems)
                for (childKey in children) {
                    if (childKey == currentKey) {
                        continue
                    }
                    addAll(result[childKey].orEmpty())
                }
            }
            result[currentKey] = items
            stack.removeLast()
            onStack -= currentKey
        }
    }
}

/**
 * Builds a [FolderHierarchyIndex] from a flat collection of [folders].
 *
 * Folders are grouped per account, so identical paths or ids in different
 * accounts never collide. In [FolderHierarchyMode.Path] folders with the same
 * path collapse into a single node. In [FolderHierarchyMode.ParentId] a folder
 * pointing at a missing parent, or one whose parent chain loops back on itself,
 * is re-rooted (`parentKey == null`) so that every node remains reachable from
 * `childrenOf(null)`.
 */
fun <T : Any> createFolderHierarchyIndex(
    folders: Collection<T>,
    accountId: (T) -> String,
    lens: (T) -> String,
    id: (T) -> String,
    parentId: (T) -> String?,
    hierarchyMode: (T) -> FolderHierarchyMode,
): FolderHierarchyIndex<T> {
    val records = folders
        .groupBy(accountId)
        .flatMap { (accountId, accountFolders) ->
            val foldersById = accountFolders.associateBy(id)
            accountFolders
                .map { folder ->
                    createFolderHierarchyIndexRecord(
                        accountId = accountId,
                        accountFolders = accountFolders,
                        foldersById = foldersById,
                        folder = folder,
                        lens = lens,
                        id = id,
                        parentId = parentId,
                        hierarchyMode = hierarchyMode(folder),
                    )
                }
        }
    val nodesByKey = records
        .groupBy { it.key }
        .mapValues { (_, records) ->
            val first = records.first()
            FolderHierarchyIndexNode(
                key = first.key,
                parentKey = first.parentKey,
                name = first.name,
                depth = first.depth,
                directItems = records.map { it.folder },
            )
        }
    val childKeysByParentKey = records
        .groupBy(
            keySelector = { it.parentKey },
            valueTransform = { it.key },
        )
        .mapValues { (_, keys) ->
            keys.distinct()
        }
    return FolderHierarchyIndex(
        nodesByKey = nodesByKey,
        childKeysByParentKey = childKeysByParentKey,
    )
}

private data class FolderHierarchyIndexRecord<T : Any>(
    val key: FolderHierarchyKey,
    val parentKey: FolderHierarchyKey?,
    val name: String,
    val depth: Int,
    val folder: T,
)

private fun <T : Any> createFolderHierarchyIndexRecord(
    accountId: String,
    accountFolders: Collection<T>,
    foldersById: Map<String, T>,
    folder: T,
    lens: (T) -> String,
    id: (T) -> String,
    parentId: (T) -> String?,
    hierarchyMode: FolderHierarchyMode,
): FolderHierarchyIndexRecord<T> = when (hierarchyMode) {
    FolderHierarchyMode.Path -> {
        val hierarchy = createFolderHierarchy(
            lens = lens,
            allFolders = accountFolders,
            folder = folder,
            hierarchyMode = FolderHierarchyMode.Path,
        )
        val parent = hierarchy.nodes
            .dropLast(1)
            .lastOrNull()
            ?.folder
        FolderHierarchyIndexRecord(
            key = FolderHierarchyKey.Path(
                accountId = accountId,
                path = lens(folder),
            ),
            parentKey = parent
                ?.let {
                    FolderHierarchyKey.Path(
                        accountId = accountId,
                        path = lens(it),
                    )
                },
            name = hierarchy.nodes
                .lastOrNull()
                ?.name
                ?: lens(folder),
            depth = hierarchy.nodes.size,
            folder = folder,
        )
    }

    FolderHierarchyMode.ParentId -> {
        val folderId = id(folder)
        // Walk the parent-id chain once, guarding against cycles with a visited
        // set (mirrors createParentIdFolderHierarchy). This gives both the depth
        // and whether the folder participates in a cycle, without the O(n)
        // ancestor walk that createFolderHierarchy would perform per folder.
        val visited = mutableSetOf(folderId)
        var depth = 1
        var cyclic = false
        var ancestorId = parentId(folder)
        while (ancestorId != null) {
            val ancestor = foldersById[ancestorId]
                ?: break
            if (!visited.add(ancestorId)) {
                // The chain loops back onto an already-seen id; the cycle
                // reaches the folder itself, so re-root it.
                cyclic = true
                break
            }
            depth++
            ancestorId = parentId(ancestor)
        }
        FolderHierarchyIndexRecord(
            key = FolderHierarchyKey.Id(
                accountId = accountId,
                folderId = folderId,
            ),
            parentKey = parentId(folder)
                // Re-root a folder whose parent is missing (dangling parent)
                // or whose parent chain loops back onto itself (cycle guard).
                ?.takeUnless { cyclic }
                ?.takeIf { it in foldersById }
                ?.let { parentFolderId ->
                    FolderHierarchyKey.Id(
                        accountId = accountId,
                        folderId = parentFolderId,
                    )
                },
            name = lens(folder),
            depth = depth,
            folder = folder,
        )
    }
}
