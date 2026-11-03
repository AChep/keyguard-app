package com.artemchep.keyguard.ui.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionUtilTest {
    private data class Item(
        val id: String?,
    )

    private class FakeSelectionHandle(
        ids: Set<String>,
    ) : SelectionHandle {
        val sink = MutableStateFlow(ids)

        var setSelectionCount = 0

        override val idsFlow: StateFlow<Set<String>> get() = sink

        override fun clearSelection() {
            sink.value = emptySet()
        }

        override fun toggleSelection(itemId: String) {
            sink.value = if (itemId in sink.value) sink.value - itemId else sink.value + itemId
        }

        override fun setSelection(ids: Set<String>) {
            setSelectionCount += 1
            sink.value = ids
        }
    }

    @Test
    fun `retain removes missing ids and keeps order`() {
        val result = retainExistingSelection(
            selectedIds = linkedSetOf("c", "b", "a"),
            existingIds = setOf("a", "c"),
        )
        assertEquals(listOf("c", "a"), result?.toList())
    }

    @Test
    fun `retain returns null if nothing is removed`() {
        val result = retainExistingSelection(
            selectedIds = setOf("a", "b"),
            existingIds = setOf("a", "b", "c"),
        )
        assertNull(result)
    }

    @Test
    fun `retain removes everything if no items exist`() {
        val result = retainExistingSelection(
            selectedIds = setOf("a", "b"),
            existingIds = emptySet(),
        )
        assertEquals(emptySet(), result)
    }

    @Test
    fun `remove missing selection reacts to deleted items`() = runTest {
        val itemsFlow = MutableStateFlow(listOf(Item("a"), Item("b"), Item(null)))
        val handle = FakeSelectionHandle(setOf("a", "b"))
        val job = handle.removeMissingSelection(
            scope = backgroundScope,
            itemsFlow = itemsFlow,
        ) { it.id }
        runCurrent()
        assertEquals(setOf("a", "b"), handle.sink.value)
        assertEquals(0, handle.setSelectionCount)

        itemsFlow.value = listOf(Item("b"), Item(null))
        runCurrent()
        assertEquals(setOf("b"), handle.sink.value)
        assertEquals(1, handle.setSelectionCount)

        // Selecting an item that does not exist removes it.
        handle.toggleSelection("x")
        runCurrent()
        assertEquals(setOf("b"), handle.sink.value)
        assertEquals(2, handle.setSelectionCount)

        itemsFlow.value = emptyList()
        runCurrent()
        assertEquals(emptySet(), handle.sink.value)
        job.cancel()
    }
}
