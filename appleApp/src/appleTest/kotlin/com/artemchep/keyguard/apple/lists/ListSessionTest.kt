package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.ListSession
import com.artemchep.keyguard.apple.core.ListSessionActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListSessionTest {
    @Test
    fun `presentations isolate rows selection and actions with overlapping ids`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<PasswordHistorySnapshot>()
        val secondFrames = mutableListOf<PasswordHistorySnapshot>()
        val invoked = mutableListOf<String>()
        first.session.observe { firstFrames += it }
        second.session.observe { secondFrames += it }
        first.publish(snapshot("A"), actions("A", invoked))
        second.publish(snapshot("B"), actions("B", invoked))
        first.session.toggleSelection("row")
        first.publish(snapshot("A", selected = true), actions("A", invoked))
        second.session.invokeItemAction("action")
        first.session.invokeSelectionAction("action")
        second.session.invokeAction("action")
        first.session.clearSelection()
        assertEquals(listOf("toggle A", "item B", "selection A", "screen B", "clear A"), invoked)
        assertEquals(1, firstFrames.last().selectionCount)
        assertEquals(0, secondFrames.last().selectionCount)
        assertEquals("A", firstFrames.last().items.single().value)
        assertEquals("B", secondFrames.last().items.single().value)

        first.session.close()
        first.publish(snapshot("late"), actions("late", invoked))
        invokeAll(first.session)
        second.publish(snapshot("updated B"), actions("B", invoked))
        second.session.invokeItemAction("action")
        assertEquals(2, firstFrames.size)
        assertEquals("updated B", secondFrames.last().items.single().value)
        assertEquals(listOf("toggle A", "item B", "selection A", "screen B", "clear A", "item B"), invoked)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears password rows and every action then cancel rejects late updates`() {
        val source = Source()
        val frames = mutableListOf<PasswordHistorySnapshot>()
        val invoked = mutableListOf<String>()
        val observation = source.session.observe { frames += it }
        source.publish(snapshot("A", selected = true), actions("A", invoked))
        source.publish(PasswordHistorySnapshot.empty, ListSessionActions())
        invokeAll(source.session)
        assertEquals(emptyList(), invoked)
        assertEquals(PasswordHistorySnapshot.empty, frames.last())

        source.publish(snapshot("unlocked"), actions("unlocked", invoked))
        source.session.toggleSelection("row")
        observation.cancel()
        observation.cancel()
        source.session.close()
        source.publish(snapshot("late"), actions("late", invoked))
        invokeAll(source.session)
        assertEquals(listOf("toggle unlocked"), invoked)
        assertEquals(3, frames.size)
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    @Test
    fun `closing during the initial callback clears actions and cancels the eventual handle`() {
        var cancellations = 0
        val invoked = mutableListOf<String>()
        val session = ListSession<PasswordHistorySnapshot> { publish ->
            publish(snapshot("A"), actions("A", invoked))
            KeyguardCancellable { cancellations++ }
        }
        session.observe { session.close() }
        invokeAll(session)
        assertEquals(1, cancellations)
        assertEquals(emptyList(), invoked)
    }

    private fun invokeAll(session: ListSession<PasswordHistorySnapshot>) {
        session.invokeItemAction("action")
        session.invokeSelectionAction("action")
        session.invokeAction("action")
        session.invokePrimaryAction()
        session.toggleSelection("row")
        session.clearSelection()
    }

    private fun snapshot(value: String, selected: Boolean = false) = PasswordHistorySnapshot(
        loaded = true,
        notFound = false,
        items = listOf(
            PasswordHistoryItemSnapshot(
                id = "row",
                value = value,
                date = null,
                monospace = false,
                selected = selected,
                selecting = selected,
            ),
        ),
        selectionCount = if (selected) 1 else 0,
    )

    private fun actions(id: String, invoked: MutableList<String>) = ListSessionActions(
        items = mapOf("action" to { invoked += "item $id" }),
        selection = mapOf("action" to { invoked += "selection $id" }),
        screen = mapOf("action" to { invoked += "screen $id" }),
        toggleSelection = { row -> if (row == "row") invoked += "toggle $id" },
        clearSelection = { invoked += "clear $id" },
        primary = { invoked += "primary $id" },
    )

    private class Source {
        lateinit var publish: (PasswordHistorySnapshot, ListSessionActions) -> Unit
        var cancellations = 0
        val session = ListSession<PasswordHistorySnapshot> { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
