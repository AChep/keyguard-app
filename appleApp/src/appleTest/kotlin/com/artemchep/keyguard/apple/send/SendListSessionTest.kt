package com.artemchep.keyguard.apple.send

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.ListSessionActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendListSessionTest {
    @Test
    fun `simultaneous lists isolate queries selection menus and file drops`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<SendListSnapshot>()
        val secondFrames = mutableListOf<SendListSnapshot>()
        val invoked = mutableListOf<String>()
        first.session.observe { firstFrames += it }
        second.session.observe { secondFrames += it }
        first.publish(snapshot("first", 1), actions("A", invoked))
        second.publish(snapshot("second", 0), actions("B", invoked))
        first.session.setQuery("typed")
        second.session.invokeFilter("shared")
        first.session.invokeSort("shared")
        second.session.toggleSelection("row")
        first.session.invokeSelectionAction("shared")
        second.session.invokeAction("shared")
        first.session.dropFile("file:///tmp/input", "input", 42)
        assertEquals(
            listOf("query A:typed", "filter B", "sort A", "toggle B", "selection A", "screen B",
                "drop A:file:///tmp/input:input:42"),
            invoked,
        )
        assertEquals("first", firstFrames.last().query)
        assertEquals("second", secondFrames.last().query)
        assertEquals(1, firstFrames.last().selectionCount)
        assertEquals(0, secondFrames.last().selectionCount)

        invoked.clear()
        first.session.close()
        first.publish(snapshot("late", 2), actions("late", invoked))
        invokeAll(first.session)
        second.publish(snapshot("updated", 1), actions("new B", invoked))
        second.session.clearFilters()
        second.session.clearSort()
        second.session.clearSelection()
        assertEquals(listOf("clear filters new B", "clear sort new B", "clear selection new B"), invoked)
        assertEquals(1, firstFrames.size)
        assertEquals("updated", secondFrames.last().query)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears every action and cancellation rejects late frames`() {
        val source = Source()
        val frames = mutableListOf<SendListSnapshot>()
        val invoked = mutableListOf<String>()
        val observation = source.session.observe { frames += it }
        source.publish(snapshot("secret", 2), actions("A", invoked))
        source.publish(SendListSnapshot.empty, SendListSessionActions())
        invokeAll(source.session)
        assertEquals(emptyList(), invoked)
        assertEquals(SendListSnapshot.empty, frames.last())

        source.publish(snapshot("unlocked", 0), actions("unlocked", invoked))
        source.session.setQuery("fresh")
        observation.cancel()
        observation.cancel()
        source.session.close()
        source.publish(snapshot("late", 1), actions("late", invoked))
        invokeAll(source.session)
        assertEquals(listOf("query unlocked:fresh"), invoked)
        assertEquals(3, frames.size)
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    @Test
    fun `closing from the initial callback cancels the eventual observer`() {
        var cancellations = 0
        val invoked = mutableListOf<String>()
        val session = SendListSession { publish ->
            publish(snapshot("initial", 0), actions("A", invoked))
            KeyguardCancellable { cancellations++ }
        }
        session.observe { session.close() }
        invokeAll(session)
        assertEquals(1, cancellations)
        assertEquals(emptyList(), invoked)
    }

    private fun invokeAll(session: SendListSession) {
        session.setQuery("late")
        session.invokeFilter("shared")
        session.invokeSort("shared")
        session.clearFilters()
        session.clearSort()
        session.toggleSelection("row")
        session.invokeSelectionAction("shared")
        session.invokeAction("shared")
        session.clearSelection()
        session.dropFile("file:///tmp/late", null, -1)
    }

    private fun snapshot(query: String, selected: Int) = SendListSnapshot.empty.copy(
        loaded = true,
        query = query,
        selectionCount = selected,
    )

    private fun actions(id: String, invoked: MutableList<String>) = SendListSessionActions(
        list = ListSessionActions(
            screen = mapOf("shared" to { invoked += "screen $id" }),
            selection = mapOf("shared" to { invoked += "selection $id" }),
            toggleSelection = { row -> if (row == "row") invoked += "toggle $id" },
            clearSelection = { invoked += "clear selection $id" },
        ),
        query = { invoked += "query $id:$it" },
        filters = mapOf("shared" to { invoked += "filter $id" }),
        sort = mapOf("shared" to { invoked += "sort $id" }),
        clearFilters = { invoked += "clear filters $id" },
        clearSort = { invoked += "clear sort $id" },
        dropFile = { uri, name, size -> invoked += "drop $id:$uri:$name:$size" },
    )

    private class Source {
        lateinit var publish: (SendListSnapshot, SendListSessionActions) -> Unit
        var cancellations = 0
        val session = SendListSession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
