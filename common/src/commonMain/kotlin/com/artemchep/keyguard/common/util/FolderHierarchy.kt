package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.model.FolderHierarchyMode

data class FolderHierarchy<T : Any>(
    val folder: T,
    val nodes: List<FolderHierarchyNode<T>>,
)

data class FolderHierarchyNode<T : Any>(
    val name: String,
    val folder: T,
)

/**
 * Builds the chain of ancestor folders for the given [folder], ordered from the
 * topmost root down to [folder] itself (inclusive).
 *
 * Invariants:
 * - [FolderHierarchyMode.Path] derives the parent by stripping the last
 *   path segment of the name; same-name folders collapse onto each other.
 * - [FolderHierarchyMode.ParentId] follows the [parentId] chain. A dangling
 *   parent (not present in [allFolders]) terminates the walk, re-rooting the
 *   folder. The walk is cycle-guarded by a visited set, so a self-parent or a
 *   loop terminates instead of recursing forever.
 */
fun <T : Any> createFolderHierarchy(
    lens: (T) -> String,
    allFolders: Collection<T>,
    folder: T,
    id: (T) -> String = lens,
    parentId: (T) -> String? = { null },
    hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
): FolderHierarchy<T> = when (hierarchyMode) {
    FolderHierarchyMode.Path -> createPathFolderHierarchy(
        lens = lens,
        allFolders = allFolders,
        folder = folder,
    )

    FolderHierarchyMode.ParentId -> createParentIdFolderHierarchy(
        lens = lens,
        id = id,
        parentId = parentId,
        allFolders = allFolders,
        folder = folder,
    )
}

/**
 * The character that separates segments of a folder path (e.g. `a/b/c`).
 * Shared so that callers building or matching folder paths do not hardcode it.
 */
const val FOLDER_HIERARCHY_DELIMITER: Char = '/'

/**
 * String form of [FOLDER_HIERARCHY_DELIMITER], convenient for `joinToString`
 * separators and prefix checks.
 */
const val FOLDER_HIERARCHY_DELIMITER_STRING: String = "/"

private val trimFolderHierarchyPrefixRegex = "^\\s*/\\s*".toRegex()

/**
 * Returns `true` when [prefix] addresses [path] itself or one of its ancestors,
 * matching only on whole path segments. `a/b` is a prefix of `a/b` and `a/b/c`,
 * but not of `a/bc`.
 */
fun isPathPrefixOf(
    prefix: String,
    path: String,
): Boolean = path == prefix ||
        path.startsWith(prefix + FOLDER_HIERARCHY_DELIMITER)

/**
 * Replaces [oldPrefix] with [newPrefix] in [path], matching on whole path
 * segments (see [isPathPrefixOf]). Returns the rewritten path, or `null` when
 * [oldPrefix] does not prefix [path].
 */
fun replacePathPrefix(
    path: String,
    oldPrefix: String,
    newPrefix: String,
): String? = when {
    path == oldPrefix -> newPrefix
    path.startsWith(oldPrefix + FOLDER_HIERARCHY_DELIMITER) ->
        newPrefix + path.removePrefix(oldPrefix)

    else -> null
}

private fun <T : Any> createPathFolderHierarchy(
    lens: (T) -> String,
    allFolders: Collection<T>,
    folder: T,
): FolderHierarchy<T> {
    val rawHierarchy = folder.hierarchyIn(
        lens = lens,
        folders = allFolders,
    )
        .toList()
        .asReversed()
    val nodes = rawHierarchy
        .mapIndexed { index, item ->
            val name = if (index > 0) {
                val parent = rawHierarchy[index - 1]
                lens(item)
                    .substringAfter(lens(parent))
                    .replace(trimFolderHierarchyPrefixRegex, "")
            } else {
                lens(item)
            }
            FolderHierarchyNode(
                name = name,
                folder = item,
            )
        }
    return FolderHierarchy(
        folder = folder,
        nodes = nodes,
    )
}

private fun <T : Any> createParentIdFolderHierarchy(
    lens: (T) -> String,
    id: (T) -> String,
    parentId: (T) -> String?,
    allFolders: Collection<T>,
    folder: T,
): FolderHierarchy<T> {
    val foldersById = allFolders.associateBy(id)
    val rawHierarchy = mutableListOf<T>()
    val visited = mutableSetOf<String>()
    var current: T? = folder
    while (current != null) {
        val currentId = id(current)
        if (!visited.add(currentId)) {
            break
        }
        rawHierarchy += current
        current = parentId(current)
            ?.let(foldersById::get)
    }

    val nodes = rawHierarchy
        .asReversed()
        .map { item ->
            FolderHierarchyNode(
                name = lens(item),
                folder = item,
            )
        }
    return FolderHierarchy(
        folder = folder,
        nodes = nodes,
    )
}

private fun <T : Any> T.hierarchyIn(
    lens: (T) -> String,
    folders: Collection<T>,
) = generateSequence(this) {
    findFolderParentOrNull(
        lens = lens,
        folders = folders,
        folder = it,
    )
}

private fun <T : Any> findFolderParentOrNull(
    lens: (T) -> String,
    folders: Collection<T>,
    folder: T,
) = findFolderParentByNameOrNull(
    lens = lens,
    folders = folders,
    name = lens(folder),
)

private tailrec fun <T : Any> findFolderParentByNameOrNull(
    lens: (T) -> String,
    folders: Collection<T>,
    name: String,
): T? {
    val index = name.indexOfLast { it == FOLDER_HIERARCHY_DELIMITER }
    if (index == -1) {
        return null
    }

    val parentName = name.substring(0, index)
    return folders
        .firstOrNull { lens(it) == parentName }
        ?: findFolderParentByNameOrNull(
            lens = lens,
            folders = folders,
            name = parentName,
        )
}
