package com.artemchep.keyguard.apple.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DetailSessionTest {
    @Test
    fun `forms keep draft actions and completion independent`() {
        val first = Source()
        val second = Source()
        val frames = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var firstClosed = 0
        var secondClosed = 0
        first.observe({ frames += "first:$it" }, { firstClosed++ })
        second.observe({ frames += "second:$it" }, { secondClosed++ })
        first.publish("A") { actions += "A" }
        second.publish("B") { actions += "B" }
        first.session.withActions { it() }
        second.session.withActions { it() }
        first.complete()
        first.complete()
        first.publish("late") { actions += "late" }
        first.session.withActions { it() }
        second.publish("edited B") { actions += "edited B" }
        second.session.withActions { it() }
        assertEquals(listOf("first:A", "second:B", "second:edited B"), frames)
        assertEquals(listOf("A", "B", "edited B"), actions)
        assertEquals(1, firstClosed)
        assertEquals(0, secondClosed)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `dismissed form ignores late completion snapshots and actions`() {
        val source = Source()
        var events = 0
        val observation = source.observe({ events++ }, { events++ })
        source.publish("draft") { events++ }
        observation.cancel()
        source.complete()
        source.publish("late") { events++ }
        source.session.withActions { it() }
        source.session.close()
        assertEquals(1, events)
        assertEquals(1, source.cancellations)
    }

    @Test
    fun `synchronous completion cancels the source as soon as subscription returns`() {
        val session = DetailSession<String, () -> Unit>()
        var closed = 0
        var cancellations = 0
        session.observe({}, { closed++ }) { _, complete ->
            complete()
            KeyguardCancellable { cancellations++ }
        }
        assertEquals(1, closed)
        assertEquals(1, cancellations)
    }

    @Test
    fun `close disposes once and silences gated channels`() {
        var disposed = 0
        val session = DetailSession<String, Unit>(onDispose = { disposed++ })
        val values = mutableListOf<Int>()
        val channel = session.gated<Int> { values += it }
        session.observe({}) { KeyguardCancellable {} }
        channel(1)
        session.close()
        session.close()
        channel(2)
        assertEquals(listOf(1), values)
        assertEquals(1, disposed)
    }

    private class Source {
        val session = DetailSession<String, () -> Unit>()
        lateinit var publish: (String, () -> Unit) -> Unit
        lateinit var complete: () -> Unit
        var cancellations = 0

        fun observe(onChange: (String) -> Unit, onClose: () -> Unit) =
            session.observe(onChange, onClose) { publish, complete ->
                this.publish = publish
                this.complete = complete
                KeyguardCancellable { cancellations++ }
            }
    }
}
