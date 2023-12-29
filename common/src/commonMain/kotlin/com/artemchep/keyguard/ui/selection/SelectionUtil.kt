package com.artemchep.keyguard.ui.selection

import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

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

    interceptBackPress(
        // Intercept the back button while the
        // selection set is not empty.
        isEnabledFlow = itemIdsSink
            .map { it.isNotEmpty() },
    ) { callback ->
        val wereEmpty = itemIdsSink.value.isEmpty()
        // Reset the selection on back
        // button press.
        itemIdsSink.value = emptySet()

        // Eat the callback if it weren't empty.
        callback(wereEmpty)
    }

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
