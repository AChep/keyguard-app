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

    nodes
        .sortedBy { it.depth }
        .forEach { node ->
            val newTitle = namesByNodeKey[node.key]
                ?.trim()
                ?.takeUnless { it.isEmpty() }
                ?: return@forEach
            when (node.folder.hierarchyMode) {
                FolderHierarchyMode.ParentId -> {
                    folderNamesById[node.folder.id] = newTitle
                }

                FolderHierarchyMode.Path -> {
                    // An already-processed selected ancestor may have rewritten
                    // this folder's path. Read the live working value rather
                    // than the immutable route or hierarchy key.
                    val currentPrefix = folderNamesById[node.folder.id]
                        ?: node.folder.name
                    val parentPrefix = node.hierarchyParentKey
                        ?.folderId
                        ?.let(folderNamesById::get)
                        ?: node.pathParentPath
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
