package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection

/**
 * The folder side of an import plan for a single source account: the folders
 * to create (parents before children) and the planned folder each item id
 * belongs to.
 */
internal data class CxfImportFolderPlan(
    val folders: List<CxfImportPlan.Folder>,
    /**
     * Maps a CXF item id to the [key][CxfImportPlan.Folder.key] of the first
     * collection (in pre-order document order) referencing it. Keyguard
     * folders are single-valued, so an item linked from several collections
     * keeps only the first link.
     */
    val folderKeyByItemId: Map<String, String>,
)

/**
 * Walks a source account's collection tree into a flat folder plan. Keyguard's
 * own exporter maps folders to nested collections, so importing collections
 * back preserves the folder hierarchy.
 *
 * [keyPrefix] namespaces the plan-local folder keys per source account, so
 * that merging several source accounts into one target account never
 * collides. Links to items of a different account
 * ([CxfLinkedItem.account][com.artemchep.keyguard.common.service.credentialexchange.model.CxfLinkedItem.account]
 * not matching [accountId]) are ignored.
 */
internal fun buildImportFolderPlan(
    collections: List<CxfCollection>,
    accountId: String?,
    keyPrefix: String,
): CxfImportFolderPlan {
    val folders = mutableListOf<CxfImportPlan.Folder>()
    val folderKeyByItemId = mutableMapOf<String, String>()
    var counter = 0
    // Subtrees re-rooted at the depth cap. Draining them here keeps the walk's
    // recursion bounded without dropping anything: only the parent edge at the
    // boundary is lost, never a folder and never an item link.
    val pending = ArrayDeque<CxfCollection>()
    fun walk(
        collection: CxfCollection,
        parentKey: String?,
        depth: Int,
    ) {
        val key = "$keyPrefix/${counter++}"
        folders += CxfImportPlan.Folder(
            key = key,
            parentKey = parentKey,
            title = collection.title.takeIf { it.isNotBlank() }
                ?: DEFAULT_FOLDER_TITLE,
        )
        collection.items.forEach { link ->
            val foreign = link.account != null &&
                accountId != null &&
                link.account != accountId
            if (!foreign) {
                folderKeyByItemId.getOrPut(link.item) { key }
            }
        }
        // The walk recurses over untrusted nesting, so it is bounded — as
        // policy, not as a stack guard: by the time it runs the document has
        // already survived the strictly deeper JSON parse and decode. Past the
        // bound a subtree is re-rooted as a new top-level folder instead of
        // dropped, so nothing is lost and nothing is counted.
        if (depth >= CXF_MAX_COLLECTION_DEPTH) {
            collection.subCollections
                .orEmpty()
                .forEach(pending::addLast)
            return
        }
        collection.subCollections
            .orEmpty()
            .forEach { child ->
                walk(collection = child, parentKey = key, depth = depth + 1)
            }
    }
    collections.forEach { collection ->
        walk(collection = collection, parentKey = null, depth = 1)
    }
    while (pending.isNotEmpty()) {
        walk(collection = pending.removeFirst(), parentKey = null, depth = 1)
    }
    return CxfImportFolderPlan(
        folders = folders,
        folderKeyByItemId = folderKeyByItemId,
    )
}

/**
 * The title given to a folder whose source collection has a blank title. Not
 * translated: it names external data on the wire-format layer, which has no
 * translator access, and a blank source title is a degenerate case.
 */
internal const val DEFAULT_FOLDER_TITLE = "Folder"
