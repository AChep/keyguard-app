package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.imports.ImportPlan

/**
 * Narrows an import plan to the selected item indexes.
 *
 * Keeping every item returns the original plan verbatim, preserving standalone
 * folders. A subset retains only the folders
 * assigned to selected items and their ancestors. A genuine folders-only plan also
 * remains unchanged, while deselecting every item from a plan that had items produces
 * an empty, non-importable plan.
 */
internal fun ImportPlan.selectItems(
    selectedItemIndexes: Set<Int>,
): ImportPlan {
    val allItemsSelected = items.indices.all(selectedItemIndexes::contains)
    val selectedItems = items.filterIndexed { index, _ ->
        index in selectedItemIndexes
    }
    return if (allItemsSelected) {
        this
    } else if (selectedItems.isEmpty()) {
        copy(
            folders = emptyList(),
            items = emptyList(),
        )
    } else {
        val folderByKey = folders.associateBy(ImportPlan.Folder::key)
        val requiredFolderKeys = mutableSetOf<String>()
        selectedItems.forEach { item ->
            var folderKey = item.folderKey
            while (folderKey != null && requiredFolderKeys.add(folderKey)) {
                folderKey = folderByKey[folderKey]?.parentKey
            }
        }
        copy(
            folders = folders.filter { folder ->
                folder.key in requiredFolderKeys
            },
            items = selectedItems,
        )
    }
}
