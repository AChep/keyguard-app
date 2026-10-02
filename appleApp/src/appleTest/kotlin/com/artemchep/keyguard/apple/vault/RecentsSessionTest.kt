package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecentsSessionTest {
    @Test
    fun `sheets keep tab commands and small channels independent`() {
        val first = Source()
        val second = Source()
        val firstTabs = mutableListOf<RecentsTabsSnapshot>()
        val secondTabs = mutableListOf<RecentsTabsSnapshot>()
        val firstTotp = mutableListOf<Map<String, TotpFieldSnapshot>>()
        val secondTotp = mutableListOf<Map<String, TotpFieldSnapshot>>()
        first.session.observe({}, { firstTabs += it }, { firstTotp += it })
        second.session.observe({}, { secondTabs += it }, { secondTotp += it })
        first.publish("recent")
        second.publish("popular")
        first.totp(mapOf("row" to TotpFieldSnapshot.loading))
        second.totp(mapOf("row" to TotpFieldSnapshot.error))
        first.session.setTab("popular")
        assertEquals(listOf("popular"), first.commands)
        assertEquals(emptyList(), second.commands)
        assertEquals("recent", firstTabs.last().selectedTabKey)
        assertEquals("popular", secondTabs.last().selectedTabKey)
        assertEquals(TotpFieldSnapshot.loading, firstTotp.last()["row"])
        assertEquals(TotpFieldSnapshot.error, secondTotp.last()["row"])

        first.session.close()
        first.session.setTab("late")
        first.publish("late")
        first.totp(emptyMap())
        second.session.setTab("recent")
        second.publish("updated")
        assertEquals(1, firstTabs.size)
        assertEquals(1, firstTotp.size)
        assertEquals(listOf("recent"), second.commands)
        assertEquals("updated", secondTabs.last().selectedTabKey)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `lock clears tab commands and close rejects queued small snapshots`() {
        val source = Source()
        val frames = mutableListOf<RecentsTabsSnapshot>()
        val subscription = source.session.observe({}, { frames += it }, {})
        source.publish("recent")
        source.tabs(RecentsTabsSnapshot.empty, null)
        source.session.setTab("popular")
        assertEquals(emptyList(), source.commands)
        source.publish("unlocked")
        source.session.setTab("recent")
        subscription.cancel()
        subscription.cancel()
        source.session.close()
        source.publish("late")
        source.session.setTab("late")
        assertEquals(listOf("recent"), source.commands)
        assertEquals(3, frames.size)
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe({}, {}, {}) }
    }

    @Test
    fun `closing from the first tab snapshot cancels all channels exactly once`() {
        var cancellations = 0
        var totpCalls = 0
        var selections = 0
        val session = RecentsSession { _, tabs, totp ->
            tabs(RecentsTabsSnapshot.empty) { selections++ }
            totp(emptyMap())
            KeyguardCancellable { cancellations++ }
        }
        session.observe({}, { session.close() }, { totpCalls++ })
        session.setTab("late")
        assertEquals(1, cancellations)
        assertEquals(0, totpCalls)
        assertEquals(0, selections)
    }

    private class Source {
        lateinit var tabs: (RecentsTabsSnapshot, ((String) -> Unit)?) -> Unit
        lateinit var totp: (Map<String, TotpFieldSnapshot>) -> Unit
        val commands = mutableListOf<String>()
        var cancellations = 0
        val session = RecentsSession { _, tabs, totp ->
            this.tabs = tabs
            this.totp = totp
            KeyguardCancellable { cancellations++ }
        }

        fun publish(key: String) {
            tabs(RecentsTabsSnapshot(true, emptyList(), key)) { commands += it }
        }
    }
}
