package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.model.FolderHierarchyMode

sealed interface FolderHierarchyKey {
    val accountId: String
    val folderId: String

    data class Path(
        override val accountId: String,
        override val folderId: String,
        val path: String,
    ) : FolderHierarchyKey

    data class Id(
        override val accountId: String,
        override val folderId: String,
    ) : FolderHierarchyKey
}

data class FolderHierarchyIndexNode<T : Any>(
    val key: FolderHierarchyKey,
    val parentKey: FolderHierarchyKey?,
    val name: String,
    val depth: Int,
    val item: T,
)

/**
 * A flattened, queryable view of one or more folder trees.
 *
 * Invariants (established by [createFolderHierarchyIndex]):
 * - Nodes are scoped per account; keys carry their `accountId` and never cross
 *   account boundaries.
 * - Every physical folder has its own node, including folders that share a path.
 * - In [FolderHierarchyMode.Path], a child with an ambiguous same-path parent is
 *   attached to the parent with the lexicographically smallest stable folder id.
 * - A folder whose parent is missing (dangling parent) is re-rooted: its
 *   `parentKey` is `null`.
 * - Parent-id cycles (a self-parent, or `A -> B -> A`) are broken by re-rooting
 *   the offending folder, so every node is reachable from `childrenOf(null)`.
 */
class FolderHierarchyIndex<T : Any> internal constructor(
    private val nodesByKey: Map<FolderHierarchyKey, FolderHierarchyIndexNode<T>>,
    private val childNodesByParentKey: Map<FolderHierarchyKey?, List<FolderHierarchyIndexNode<T>>>,
    private val keyOfFolder: (T) -> FolderHierarchyKey,
) {
    private data class ItemKey(
        val accountId: String,
        val folderId: String,
    )

    /**
     * Every node, in the first-appearance order of the folders that produced
     * them. Computed once at construction, so the instance is stable.
     */
    val nodes: List<FolderHierarchyIndexNode<T>> = nodesByKey.values.toList()

    private val nodesByItemKey = nodesByKey.values
        .associateBy { node ->
            ItemKey(
                accountId = node.key.accountId,
                folderId = node.key.folderId,
            )
        }

    fun node(
        key: FolderHierarchyKey,
    ): FolderHierarchyIndexNode<T>? = nodesByKey[key]

    /** The key this index records [folder] under. */
    fun keyOf(
        folder: T,
    ): FolderHierarchyKey = keyOfFolder(folder)

    /** The node [folder] belongs to, or `null` when it was not indexed. */
    fun nodeOf(
        folder: T,
    ): FolderHierarchyIndexNode<T>? = nodesByKey[keyOfFolder(folder)]

    fun nodeOf(
        accountId: String,
        folderId: String,
    ): FolderHierarchyIndexNode<T>? = nodesByItemKey[
        ItemKey(
            accountId = accountId,
            folderId = folderId,
        )
    ]

    fun childrenOf(
        parentKey: FolderHierarchyKey?,
    ): List<FolderHierarchyIndexNode<T>> = childNodesByParentKey[parentKey].orEmpty()

    fun descendantsOf(
        key: FolderHierarchyKey,
    ): List<T> = buildList {
        visitSubtree(key) { item ->
            add(item)
            false
        }
    }

    /**
     * Returns whether [predicate] matches this node or any of its descendants.
     * Unlike [descendantsOf], this does not allocate a subtree list and stops at
     * the first match.
     */
    fun anyDescendant(
        key: FolderHierarchyKey,
        predicate: (T) -> Boolean,
    ): Boolean = visitSubtree(key, predicate)

    /**
     * Visits a subtree iteratively in parent-first order. Returning `true` from
     * [visitor] stops the traversal and is propagated to the caller.
     */
    private fun visitSubtree(
        startKey: FolderHierarchyKey,
        visitor: (T) -> Boolean,
    ): Boolean {
        val stack = ArrayDeque<FolderHierarchyIndexNode<T>>()
        val visited = mutableSetOf<FolderHierarchyKey>()
        // An unknown start key leaves the stack empty, which is the same
        // "visited nothing, stopped nowhere" answer as an exhausted walk.
        nodesByKey[startKey]?.let(stack::addLast)
        var stopped = false
        while (!stopped && stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (visited.add(node.key)) {
                stopped = visitor(node.item)
                if (!stopped) {
                    val children = childNodesByParentKey[node.key].orEmpty()
                    for (index in children.lastIndex downTo 0) {
                        stack.addLast(children[index])
                    }
                }
            }
        }
        return stopped
    }
}

/**
 * Builds a [FolderHierarchyIndex] from a flat collection of [folders].
 *
 * Folders are partitioned by account and hierarchy mode, so identical paths or
 * ids in different accounts never collide and mixed hierarchy modes cannot
 * become each other's ancestors. Every physical folder remains a separate node.
 * In [FolderHierarchyMode.ParentId] a folder pointing at a missing parent, or
 * one whose parent chain loops back on itself, is re-rooted
 * (`parentKey == null`) so that every node remains reachable from
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
            accountFolders
                .groupBy(hierarchyMode)
                .flatMap { (mode, modeFolders) ->
                    when (mode) {
                        FolderHierarchyMode.Path -> createPathFolderHierarchyIndexRecords(
                            accountId = accountId,
                            folders = modeFolders,
                            lens = lens,
                            id = id,
                        )

                        FolderHierarchyMode.ParentId -> createParentIdFolderHierarchyIndexRecords(
                            accountId = accountId,
                            folders = modeFolders,
                            lens = lens,
                            id = id,
                            parentId = parentId,
                        )
                    }
                }
        }
    val nodesByKey = records
        .associate { record ->
            record.key to FolderHierarchyIndexNode(
                key = record.key,
                parentKey = record.parentKey,
                name = record.name,
                depth = record.depth,
                item = record.folder,
            )
        }
    val childNodesByParentKey = records
        .groupBy(
            keySelector = { it.parentKey },
            valueTransform = { it.key },
        )
        .mapValues { (_, childKeys) ->
            childKeys
                .distinct()
                .mapNotNull(nodesByKey::get)
        }
    return FolderHierarchyIndex(
        nodesByKey = nodesByKey,
        childNodesByParentKey = childNodesByParentKey,
        keyOfFolder = { folder ->
            folderHierarchyKeyOf(
                folder = folder,
                accountId = accountId,
                lens = lens,
                id = id,
                hierarchyMode = hierarchyMode,
            )
        },
    )
}

/**
 * Mirrors the keying rule [createFolderHierarchyIndex] builds its records with,
 * so [FolderHierarchyIndex.keyOf] can answer without a lookup table keyed by the
 * folder itself.
 */
private fun <T : Any> folderHierarchyKeyOf(
    folder: T,
    accountId: (T) -> String,
    lens: (T) -> String,
    id: (T) -> String,
    hierarchyMode: (T) -> FolderHierarchyMode,
): FolderHierarchyKey = when (hierarchyMode(folder)) {
    FolderHierarchyMode.Path -> FolderHierarchyKey.Path(
        accountId = accountId(folder),
        folderId = id(folder),
        path = lens(folder),
    )

    FolderHierarchyMode.ParentId -> FolderHierarchyKey.Id(
        accountId = accountId(folder),
        folderId = id(folder),
    )
}

private data class FolderHierarchyIndexRecord<T : Any>(
    val key: FolderHierarchyKey,
    val parentKey: FolderHierarchyKey?,
    val name: String,
    val depth: Int,
    val folder: T,
)

private fun <T : Any> createPathFolderHierarchyIndexRecords(
    accountId: String,
    folders: List<T>,
    lens: (T) -> String,
    id: (T) -> String,
): List<FolderHierarchyIndexRecord<T>> {
    // A path can belong to multiple physical folders. Pick its owner directly
    // instead of sorting the whole account, keeping the choice deterministic.
    val ownersByPath = mutableMapOf<String, T>()
    folders.forEach { folder ->
        val path = lens(folder)
        val currentOwner = ownersByPath[path]
        if (currentOwner == null || id(folder) < id(currentOwner)) {
            ownersByPath[path] = folder
        }
    }
    val parentPaths = ownersByPath.keys
        .associateWith { path ->
            findNearestParentPath(
                path = path,
                knownPaths = ownersByPath.keys,
            )
        }
    // Parent paths are always shorter, so calculating shallow paths first lets
    // each depth reuse its already-computed parent depth.
    val depthsByPath = mutableMapOf<String, Int>()
    parentPaths.keys
        .sortedBy { it.length }
        .forEach { path ->
            val parentDepth = parentPaths[path]
                ?.let(depthsByPath::get)
                ?: 0
            depthsByPath[path] = parentDepth + 1
        }

    return folders.map { folder ->
        val folderId = id(folder)
        val path = lens(folder)
        val parentPath = parentPaths[path]
        FolderHierarchyIndexRecord(
            key = FolderHierarchyKey.Path(
                accountId = accountId,
                folderId = folderId,
                path = path,
            ),
            parentKey = parentPath
                ?.let { resolvedParentPath ->
                    val parent = ownersByPath.getValue(resolvedParentPath)
                    FolderHierarchyKey.Path(
                        accountId = accountId,
                        folderId = id(parent),
                        path = resolvedParentPath,
                    )
                },
            name = parentPath
                ?.let { path.substring(it.length + 1).trimStart() }
                ?: path,
            depth = depthsByPath.getValue(path),
            folder = folder,
        )
    }
}

private fun findNearestParentPath(
    path: String,
    knownPaths: Set<String>,
): String? {
    var candidate = path
    while (true) {
        val delimiterIndex = candidate
            .indexOfLast { it == FOLDER_HIERARCHY_DELIMITER }
        if (delimiterIndex == -1) {
            return null
        }
        candidate = candidate.substring(0, delimiterIndex)
        if (candidate in knownPaths) {
            return candidate
        }
    }
}

private fun <T : Any> createParentIdFolderHierarchyIndexRecords(
    accountId: String,
    folders: List<T>,
    lens: (T) -> String,
    id: (T) -> String,
    parentId: (T) -> String?,
): List<FolderHierarchyIndexRecord<T>> {
    val foldersById = folders.associateBy(id)
    val resolutionsById = resolveParentIdFolders(
        foldersById = foldersById,
        parentId = parentId,
    )
    return folders.map { folder ->
        val folderId = id(folder)
        val resolution = resolutionsById.getValue(folderId)
        FolderHierarchyIndexRecord(
            key = FolderHierarchyKey.Id(
                accountId = accountId,
                folderId = folderId,
            ),
            parentKey = parentId(folder)
                // Re-root a folder whose parent is missing (dangling parent)
                // or whose parent chain loops back onto itself (cycle guard).
                ?.takeUnless { resolution.reachesCycle }
                ?.takeIf { it in foldersById }
                ?.let { parentFolderId ->
                    FolderHierarchyKey.Id(
                        accountId = accountId,
                        folderId = parentFolderId,
                    )
                },
            name = lens(folder),
            depth = resolution.depth,
            folder = folder,
        )
    }
}

private data class ParentIdFolderResolution(
    val depth: Int,
    val reachesCycle: Boolean,
)

/**
 * Resolves every parent-id chain once. Resolved suffixes are reused by folders
 * that join the same chain, avoiding a full ancestor walk for every node.
 */
private fun <T : Any> resolveParentIdFolders(
    foldersById: Map<String, T>,
    parentId: (T) -> String?,
): Map<String, ParentIdFolderResolution> {
    val resolutions = mutableMapOf<String, ParentIdFolderResolution>()
    foldersById.keys.forEach { startId ->
        if (startId in resolutions) {
            return@forEach
        }

        val path = mutableListOf<String>()
        val pathIndexes = mutableMapOf<String, Int>()
        var currentId: String? = startId
        var suffix = ParentIdFolderResolution(
            depth = 0,
            reachesCycle = false,
        )
        var cycleStartIndex: Int? = null
        while (currentId != null) {
            val resolved = resolutions[currentId]
            if (resolved != null) {
                suffix = resolved
                break
            }
            val currentFolder = foldersById[currentId]
                ?: break
            val previousIndex = pathIndexes[currentId]
            if (previousIndex != null) {
                cycleStartIndex = previousIndex
                break
            }
            pathIndexes[currentId] = path.size
            path += currentId
            currentId = parentId(currentFolder)
        }

        if (cycleStartIndex != null) {
            val cycleLength = path.size - cycleStartIndex
            for (index in cycleStartIndex until path.size) {
                resolutions[path[index]] = ParentIdFolderResolution(
                    depth = cycleLength,
                    reachesCycle = true,
                )
            }
            var depth = cycleLength
            for (index in cycleStartIndex - 1 downTo 0) {
                depth++
                resolutions[path[index]] = ParentIdFolderResolution(
                    depth = depth,
                    reachesCycle = true,
                )
            }
        } else {
            for (index in path.lastIndex downTo 0) {
                suffix = ParentIdFolderResolution(
                    depth = suffix.depth + 1,
                    reachesCycle = suffix.reachesCycle,
                )
                resolutions[path[index]] = suffix
            }
        }
    }
    return resolutions
}
