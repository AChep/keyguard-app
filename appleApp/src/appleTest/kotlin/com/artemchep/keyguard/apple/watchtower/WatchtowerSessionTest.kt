package com.artemchep.keyguard.apple.watchtower

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchtowerSessionTest {
    @Test
    fun `root and stacked dashboards own their filters counters and actions`() {
        val first = Source()
        val second = Source()
        val frames = mutableListOf<WatchtowerSnapshot>()
        val invoked = mutableListOf<String>()
        first.session.observe {}
        second.session.observe { frames += it }
        first.publish(snapshot(1), actions("first", invoked))
        second.publish(snapshot(2), actions("second", invoked))
        first.session.invokeWatchtowerFilter("filter")
        second.session.invokeWatchtowerAction("card")
        first.session.clearWatchtowerFilters()
        first.session.close()
        first.publish(snapshot(9), actions("late", invoked))
        first.session.invokeWatchtowerAction("card")
        second.publish(snapshot(3), actions("second", invoked))
        second.session.clearWatchtowerFilters()
        assertEquals(listOf("filter first", "card second", "clear first", "clear second"), invoked)
        assertEquals(3, frames.last().unreadCount)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `locking clears callbacks and reentrant close cancels the initial subscription`() {
        val source = Source()
        val invoked = mutableListOf<String>()
        source.session.observe {}
        source.publish(snapshot(1), actions("open", invoked))
        source.publish(WatchtowerSnapshot.empty, WatchtowerSessionActions())
        source.session.invokeWatchtowerAction("card")
        source.session.invokeWatchtowerFilter("filter")
        source.session.clearWatchtowerFilters()
        assertEquals(emptyList(), invoked)
        source.session.close()
        var cancelled = 0
        val immediate = WatchtowerSession { publish ->
            publish(snapshot(1), actions("immediate", invoked))
            KeyguardCancellable { cancelled++ }
        }
        immediate.observe { immediate.close() }
        immediate.invokeWatchtowerAction("card")
        assertEquals(1, cancelled)
        assertEquals(emptyList(), invoked)
    }

    private fun snapshot(count: Int) = WatchtowerSnapshot.empty.copy(loaded = true, unreadCount = count)
    private fun actions(owner: String, invoked: MutableList<String>) = WatchtowerSessionActions(
        actions = mapOf("card" to { invoked += "card $owner" }),
        filters = mapOf("filter" to { invoked += "filter $owner" }),
        clearFilters = { invoked += "clear $owner" },
    )

    private class Source {
        lateinit var publish: (WatchtowerSnapshot, WatchtowerSessionActions) -> Unit
        var cancellations = 0
        val session = WatchtowerSession { publish ->
            this.publish = publish
            KeyguardCancellable { cancellations++ }
        }
    }
}
