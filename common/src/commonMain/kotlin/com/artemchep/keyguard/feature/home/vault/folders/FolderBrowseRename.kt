package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER_STRING
import com.artemchep.keyguard.common.util.replacePathPrefix

internal fun createFolderRenameMap(
    nodes: List<FolderBrowseNode>,
    namesByNodeKey: Map<String, String>,
): Map<String, String> {
    val folders = nodes
        .asSequence()
        .flatMap { it.descendantFolders }
        .distinctBy { it.id }
        .toList()
    val folderNamesById = folders
        .associate { it.id to it.name }
        .toMutableMap()
    val pathRenames = mutableListOf<Pair<String, String>>()

    nodes
        .sortedBy { it.depth }
        .forEach { node ->
            val newTitle = namesByNodeKey[node.key]
                ?.trim()
                ?.takeUnless { it.isEmpty() }
                ?: return@forEach
            when (val anchor = node.anchor) {
                is FoldersRoute.Args.Parent.Id -> {
                    node.directFolders.forEach { folder ->
                        folderNamesById[folder.id] = newTitle
                    }
                }

                is FoldersRoute.Args.Parent.Path -> {
                    val currentPrefix = pathRenames.fold(anchor.path) { name, rename ->
                        renamePathPrefix(
                            name = name,
                            oldPrefix = rename.first,
                            newPrefix = rename.second,
                        ) ?: name
                    }
                    val parentPrefix = node.pathParentPath
                        ?.let { parentPath ->
                            pathRenames.fold(parentPath) { name, rename ->
                                renamePathPrefix(
                                    name = name,
                                    oldPrefix = rename.first,
                                    newPrefix = rename.second,
                                ) ?: name
                            }
                        }
                        .orEmpty()
                    val newPrefix = if (parentPrefix.isBlank()) {
                        newTitle
                    } else {
                        "$parentPrefix$FOLDER_HIERARCHY_DELIMITER_STRING$newTitle"
                    }
                    node.descendantFolders
                        .filter { it.hierarchyMode == FolderHierarchyMode.Path }
                        .forEach { folder ->
                            val currentName = folderNamesById[folder.id]
                                ?: folder.name
                            folderNamesById[folder.id] = renamePathPrefix(
                                name = currentName,
                                oldPrefix = currentPrefix,
                                newPrefix = newPrefix,
                            ) ?: currentName
                        }
                    pathRenames += currentPrefix to newPrefix
                }
            }
        }

    return folders
        .mapNotNull { folder ->
            val newName = folderNamesById[folder.id]
                ?: return@mapNotNull null
            folder.id
                .takeIf { newName != folder.name }
                ?.let { it to newName }
        }
        .toMap()
}

private fun renamePathPrefix(
    name: String,
    oldPrefix: String,
    newPrefix: String,
): String? = replacePathPrefix(
    path = name,
    oldPrefix = oldPrefix,
    newPrefix = newPrefix,
)
