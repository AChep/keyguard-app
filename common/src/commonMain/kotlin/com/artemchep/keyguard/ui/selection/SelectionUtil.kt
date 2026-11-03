package com.artemchep.keyguard.ui.selection

import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

interface SelectionHandle {
    val idsFlow: StateFlow<Set<String>>

    fun clearSelection()

    fun toggleSelection(itemId: String)

    fun setSelection(ids: Set<String>)
}

fun RememberStateFlowScope.selectionHandle(
    key: String,
): SelectionHandle {
    val itemIdsSink = mutablePersistedFlow(key) {
        setOf<String>()
    }

    // Intercept the back button while the
    // selection set is not empty.
    interceptBackPress(
        interceptorFlow = itemIdsSink
            .map { it.isNotEmpty() }
            .map { enabled ->
                 if (enabled) {
                     // lambda
                     {
                         // Reset the selection on back
                         // button press.
                         itemIdsSink.value = emptySet()
                     }
                 } else {
                     null
                 }
            },
    )

    return object : SelectionHandle {
        override val idsFlow: StateFlow<Set<String>> get() = itemIdsSink

        override fun clearSelection() {
            itemIdsSink.value = emptySet()
        }

        override fun toggleSelection(itemId: String) {
            val oldItemIds = itemIdsSink.value
            val newItemIds =
                if (itemId in oldItemIds) {
                    oldItemIds - itemId
                } else {
                    oldItemIds + itemId
                }
            itemIdsSink.value = newItemIds
        }

        override fun setSelection(ids: Set<String>) {
            itemIdsSink.value = ids
        }
    }
}

/**
 * Automatically de-selects items that do not exist
 * in the [itemsFlow]. Pass the raw list of items, otherwise
 * filtering the list would de-select the hidden items.
 */
fun <T> SelectionHandle.removeMissingSelection(
    scope: CoroutineScope,
    itemsFlow: Flow<List<T>>,
    getId: (T) -> String?,
): Job = combine(
    itemsFlow,
    idsFlow,
) { items, selectedIds ->
    if (selectedIds.isEmpty()) {
        return@combine null
    }

    val existingIds = items.mapNotNullTo(HashSet(items.size), getId)
    retainExistingSelection(
        selectedIds = selectedIds,
        existingIds = existingIds,
    )
}
    .filterNotNull()
    .onEach(::setSelection)
    .launchIn(scope)

/**
 * Returns the selected ids that exist, or `null` if
 * the selection has not changed.
 */
internal fun retainExistingSelection(
    selectedIds: Set<String>,
    existingIds: Set<String>,
): Set<String>? {
    val newSelectedIds = selectedIds
        .filterTo(LinkedHashSet()) { it in existingIds }
    return newSelectedIds.takeIf { it.size < selectedIds.size }
}
