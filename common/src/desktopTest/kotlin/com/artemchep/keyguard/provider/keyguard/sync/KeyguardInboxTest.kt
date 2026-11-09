package com.artemchep.keyguard.provider.keyguard.sync

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyguardInboxTest {
    private val inbox = KeyguardInbox()

    private fun envelope(vararg ids: String) = Envelope(
        entries = ids.map { SyncEntry(id = it, kind = EntryKind.EMAIL_FORWARDER) },
    )

    private fun record(id: String, label: String = id, at: Long = 1L) = RosterDevice(
        id = id,
        label = label,
        platform = "test",
        noisePub = "noise-$id",
        signPub = "sign-$id",
        addedAt = Hlc(1L, 0, FOUNDER),
        addedBy = FOUNDER,
        updatedAt = Hlc(at, 0, id),
    )

    private fun roster(vararg devices: RosterDevice, founder: String = FOUNDER) =
        Roster(founder = founder, devices = devices.toList()).normalized()

    @Test
    fun `only the newest envelope of each device waits`() {
        val first = envelope("1")
        val second = envelope("1", "2")
        val other = envelope("3")
        assertTrue(inbox.post(GROUP, "a", first, hash = "h1"))
        assertTrue(inbox.post(GROUP, "b", other, hash = "h3"))
        assertTrue(inbox.post(GROUP, "a", second, hash = "h2"))
        val waiting = inbox.peek(GROUP).envelopes
        assertEquals(2, waiting.size)
        assertSame(second, waiting.single { it.from == "a" }.envelope)
        assertSame(other, waiting.single { it.from == "b" }.envelope)
    }

    @Test
    fun `an envelope from the relay waits beside the one from a session`() {
        val newer = envelope("1", "2")
        val older = envelope("1")
        assertTrue(inbox.post(GROUP, "a", newer, hash = "h2"))
        // A fetch of the relay that ends after the session handed over a newer envelope.
        assertTrue(inbox.post(GROUP, "a", older, hash = "h1", source = KeyguardInbox.Source.RELAY))
        val batch = inbox.peek(GROUP)
        assertEquals(setOf(newer, older), batch.envelopes.map { it.envelope }.toSet())
        assertEquals(setOf("a"), batch.senders)
        // The same content waits once, whichever source brings it.
        assertFalse(inbox.post(GROUP, "a", envelope("1", "2"), hash = "h2", source = KeyguardInbox.Source.RELAY))
        // Each source keeps its newest one.
        val newest = envelope("1", "2", "3")
        assertTrue(inbox.post(GROUP, "a", newest, hash = "h3", source = KeyguardInbox.Source.RELAY))
        assertEquals(setOf(newer, newest), inbox.peek(GROUP).envelopes.map { it.envelope }.toSet())
        // A pass takes both.
        inbox.acknowledge(GROUP, inbox.peek(GROUP))
        assertTrue(inbox.peek(GROUP).isEmpty)
    }

    @Test
    fun `an envelope with the content of the one that waits is not queued`() {
        val first = envelope("1")
        assertTrue(inbox.post(GROUP, "a", first, hash = "h1"))
        repeat(100) {
            assertFalse(inbox.post(GROUP, "a", envelope("1"), hash = "h1"))
        }
        assertSame(first, inbox.peek(GROUP).envelopes.single().envelope)
        // Another device with the same content waits in a slot of its own.
        assertTrue(inbox.post(GROUP, "b", envelope("1"), hash = "h1"))
    }

    @Test
    fun `the rosters wait merged in one slot, which never displaces an envelope`() {
        val full = envelope("1")
        inbox.post(GROUP, "a", full, hash = "h1")
        inbox.postRoster(GROUP, "a", roster(record(FOUNDER), record("x", label = "Old")))
        inbox.postRoster(GROUP, "b", roster(record(FOUNDER), record("x", label = "New", at = 2L), record("y")))
        val batch = inbox.peek(GROUP)
        assertSame(full, batch.envelopes.single().envelope)
        // Each device keeps its newer record; nothing is lost to the order of the senders.
        val roster = checkNotNull(batch.roster)
        assertEquals(listOf(FOUNDER, "x", "y"), roster.devices.map { it.id }.sorted())
        assertEquals("New", roster.devices.single { it.id == "x" }.label)
        assertEquals(setOf("a", "b"), batch.senders)
    }

    @Test
    fun `a roster of another founder replaces the waiting one`() {
        inbox.postRoster(GROUP, "a", roster(record(FOUNDER)))
        val other = roster(record("other"), founder = "other")
        inbox.postRoster(GROUP, "b", other)
        val batch = inbox.peek(GROUP)
        assertSame(other, batch.roster)
        assertEquals(setOf("b"), batch.senders)
    }

    @Test
    fun `what arrives during a pass stays for the next one`() {
        val applied = envelope("1")
        inbox.post(GROUP, "a", applied, hash = "h1")
        inbox.postRoster(GROUP, "a", roster(record(FOUNDER)))
        val pass = inbox.peek(GROUP)
        val newer = envelope("1", "2")
        inbox.post(GROUP, "a", newer, hash = "h2")
        inbox.postRoster(GROUP, "b", roster(record(FOUNDER), record("y")))
        inbox.acknowledge(GROUP, pass)
        val next = inbox.peek(GROUP)
        assertSame(newer, next.envelopes.single().envelope)
        assertEquals(listOf(FOUNDER, "y"), next.roster?.devices?.map { it.id }?.sorted())
        inbox.acknowledge(GROUP, next)
        assertTrue(inbox.peek(GROUP).isEmpty)
        assertNull(inbox.peek(GROUP).roster)
        // An applied state that arrives again is merged again: the local state may have
        // changed, or the pass may have dropped some of it until now.
        assertTrue(inbox.post(GROUP, "a", envelope("1", "2"), hash = "h2"))
    }

    private companion object {
        const val GROUP = "group"
        const val FOUNDER = "founder"
    }
}
