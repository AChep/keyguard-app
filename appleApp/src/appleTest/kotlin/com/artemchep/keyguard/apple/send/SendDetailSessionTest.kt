package com.artemchep.keyguard.apple.send

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendDetailSessionTest {
    @Test
    fun `presentations isolate snapshots and all actions even after one closes`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<String>()
        val secondFrames = mutableListOf<String>()
        val invoked = mutableListOf<String>()
        first.session.observe { firstFrames += it.title }
        second.session.observe { secondFrames += it.title }
        first.publish(snapshot("A"), actions("A", invoked))
        second.publish(snapshot("B"), actions("B", invoked))
        first.session.invokeAction("item")
        first.session.sendCopy()
        second.session.sendShare()
        second.session.sendEdit()
        assertEquals(listOf("item A", "copy A", "share B", "edit B"), invoked)

        first.session.close()
        first.publish(snapshot("late"), actions("late", invoked))
        first.session.invokeAction("item")
        first.session.sendCopy()
        first.session.sendShare()
        first.session.sendEdit()
        second.publish(snapshot("updated B"), actions("B", invoked))
        second.session.sendCopy()
        assertEquals(listOf("A"), firstFrames)
        assertEquals(listOf("B", "updated B"), secondFrames)
        assertEquals(listOf("item A", "copy A", "share B", "edit B", "copy B"), invoked)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears every action and cancel rejects subsequent frames`() {
        val source = Source()
        val frames = mutableListOf<SendDetailSnapshot>()
        val invoked = mutableListOf<String>()
        val observation = source.session.observe { frames += it }
        source.publish(snapshot("A"), actions("A", invoked))
        source.publish(SendDetailSnapshot.empty, SendDetailActions())
        source.session.invokeAction("item")
        source.session.sendCopy()
        source.session.sendShare()
        source.session.sendEdit()
        assertEquals(emptyList(), invoked)
        assertEquals(SendDetailSnapshot.empty, frames.last())
        source.publish(snapshot("unlocked"), actions("unlocked", invoked))
        source.session.sendEdit()
        observation.cancel()
        observation.cancel()
        source.session.close()
        source.publish(snapshot("late"), actions("late", invoked))
        source.session.sendEdit()
        assertEquals(listOf("edit unlocked"), invoked)
        assertEquals("unlocked", frames.last().title)
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    @Test
    fun `closing from the initial callback cancels the eventual handle`() {
        var cancellations = 0
        val session = SendDetailSession { publish ->
            publish(snapshot("A"), SendDetailActions())
            KeyguardCancellable { cancellations++ }
        }
        session.observe { session.close() }
        assertEquals(1, cancellations)
    }

    private fun snapshot(title: String) = SendDetailSnapshot.empty.copy(title = title)

    private fun actions(id: String, invoked: MutableList<String>) = SendDetailActions(
        items = mapOf("item" to { invoked += "item $id" }),
        copy = { invoked += "copy $id" },
        share = { invoked += "share $id" },
        edit = { invoked += "edit $id" },
    )

    private class Source {
        lateinit var publish: (SendDetailSnapshot, SendDetailActions) -> Unit
        var cancellations = 0
        val session = SendDetailSession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
