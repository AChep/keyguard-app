package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.impl.DEFAULT_FOLDER_TITLE
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.common.util.FOLDER_HIERARCHY_DELIMITER_STRING

/**
 * One planned folder resolved against the target account's representation: the
 * hierarchy level it sits on (parents first) and the name to store.
 */
internal data class CxfResolvedFolder(
    val key: String,
    val parentKey: String?,
    val depth: Int,
    val name: String,
)

/**
 * Resolves the plan's folder tree into the rows to create.
 *
 * In [FolderHierarchyMode.ParentId] the name is the collection's own leaf title
 * and the tree lives in the parent edge. In [FolderHierarchyMode.Path] the name
 * carries the whole ancestor path instead. Collection titles stay verbatim:
 * Keyguard's exporter may put missing path segments into a title (for example
 * `B/C` when the real parent row is `A`), so removing the delimiter would make
 * the imported path lossy.
 *
 * Relies on the plan's documented ordering: every parent precedes its children,
 * so its depth and resolved name are already known.
 */
internal fun resolvePlannedFolders(
    folders: List<CxfImportPlan.Folder>,
    hierarchyMode: FolderHierarchyMode,
): List<CxfResolvedFolder> {
    val resolvedByKey = mutableMapOf<String, CxfResolvedFolder>()
    return folders.map { folder ->
        val parent = folder.parentKey?.let(resolvedByKey::get)
        val resolved = CxfResolvedFolder(
            key = folder.key,
            parentKey = folder.parentKey,
            depth = (parent?.depth ?: -1) + 1,
            name = when (hierarchyMode) {
                FolderHierarchyMode.ParentId -> folder.title
                FolderHierarchyMode.Path -> pathFolderName(folder.title, parent?.name)
            },
        )
        resolvedByKey[folder.key] = resolved
        resolved
    }
}

private fun pathFolderName(
    title: String,
    parentPath: String?,
): String {
    val childPath = title
        .takeIf { it.isNotBlank() }
        ?: DEFAULT_FOLDER_TITLE
    return listOfNotNull(parentPath, childPath)
        .joinToString(separator = FOLDER_HIERARCHY_DELIMITER_STRING)
}

/**
 * Creates the folders level by level and returns the created folder id per
 * plan key. [AddFolder] returns its ids in request order, which is what
 * associates each created id back to the plan folder that asked for it.
 */
internal suspend fun createPlannedFolders(
    folders: List<CxfImportPlan.Folder>,
    accountId: AccountId,
    addFolder: AddFolder,
    hierarchyMode: FolderHierarchyMode,
): Map<String, String> {
    if (folders.isEmpty()) {
        return emptyMap()
    }
    val folderIdByKey = mutableMapOf<String, String>()
    resolvePlannedFolders(folders, hierarchyMode)
        .groupBy { folder -> folder.depth }
        // Shallowest level first, so every parent exists before its children.
        // (`toSortedMap` is JVM-only and does not compile for the native targets.)
        .entries
        .sortedBy { (depth, _) -> depth }
        .forEach { (_, levelFolders) ->
            val requests = levelFolders.map { folder ->
                AddFolderRequest(
                    accountId = accountId,
                    name = folder.name,
                    // A Path folder carries its parent edge inside the name, so
                    // a second edge beside it would contradict it.
                    parentId = folder.parentKey
                        ?.takeIf { hierarchyMode == FolderHierarchyMode.ParentId }
                        ?.let(folderIdByKey::get),
                    hierarchyMode = hierarchyMode,
                )
            }
            val ids = addFolder(requests)
                .bind()
            // The `zip` below would silently truncate, filing the unpaired
            // folders' items at the account root instead.
            check(ids.size == levelFolders.size) {
                "AddFolder returned ${ids.size} ids for ${levelFolders.size} requests"
            }
            levelFolders.zip(ids) { folder, id ->
                folderIdByKey[folder.key] = id
            }
        }
    return folderIdByKey
}
