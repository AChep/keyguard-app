package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan

/**
 * Narrows an import plan to the selected item indexes.
 *
 * Keeping every item returns the original plan verbatim, preserving standalone
 * folders and today's default import behavior. A subset retains only the folders
 * assigned to selected items and their ancestors. A genuine folders-only plan also
 * remains unchanged, while deselecting every item from a plan that had items produces
 * an empty, non-importable plan.
 */
internal fun CxfImportPlan.selectItems(
    selectedItemIndexes: Set<Int>,
): CxfImportPlan {
    val allItemsSelected = items.indices.all(selectedItemIndexes::contains)
    if (allItemsSelected) {
        return this
    }

    val selectedItems = items.filterIndexed { index, _ ->
        index in selectedItemIndexes
    }
    if (selectedItems.isEmpty()) {
        return copy(
            folders = emptyList(),
            items = emptyList(),
        )
    }

    val folderByKey = folders.associateBy(CxfImportPlan.Folder::key)
    val requiredFolderKeys = mutableSetOf<String>()
    selectedItems.forEach { item ->
        var folderKey = item.folderKey
        while (folderKey != null && requiredFolderKeys.add(folderKey)) {
            folderKey = folderByKey[folderKey]?.parentKey
        }
    }

    return copy(
        folders = folders.filter { folder ->
            folder.key in requiredFolderKeys
        },
        items = selectedItems,
    )
}
