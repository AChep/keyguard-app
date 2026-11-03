package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder

/**
 * Plans a source account's collection tree as folders. Keyguard's own exporter
 * maps folders to nested collections, so importing collections back preserves
 * the folder hierarchy. Every collection is a folder of its own: siblings with
 * the same title stay apart, as do collections of different source accounts.
 *
 * Returns the folder key of each CXF item id: the first collection (in
 * pre-order document order) that links it. Keyguard folders are
 * single-valued, so an item linked from several collections keeps only the
 * first link. Links to items of a different account
 * ([CxfLinkedItem.account][com.artemchep.keyguard.common.service.credentialexchange.model.CxfLinkedItem.account]
 * not matching [accountId]) are ignored.
 */
internal fun ImportPlanBuilder.cxfCollectionFolders(
    collections: List<CxfCollection>,
    accountId: String?,
): Map<String, String> {
    val folderKeyByItemId = mutableMapOf<String, String>()
    // Subtrees re-rooted at the depth cap. Draining them here keeps the walk's
    // recursion bounded without dropping anything: only the parent edge at the
    // boundary is lost, never a folder and never an item link.
    val pending = ArrayDeque<CxfCollection>()
    fun walk(
        collection: CxfCollection,
        parentKey: String?,
        depth: Int,
    ) {
        val key = folder(
            parentKey = parentKey,
            title = collection.title,
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
    return folderKeyByItemId
}
