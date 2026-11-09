package com.artemchep.keyguard.feature.p2psync.parts

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntryMerger
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies.forwarder
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailForwarderSchemaTest {
    private val publisher = LocalEditPublisher()
    private val merger = EntryMerger
    private val id = "6f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b"
    private var time = 1_000_000L

    private val crypto = TestCrypto()

    private fun clock(node: String) = HlcClock(node = node, physicalNow = { time++ }, persist = {})

    private val budget get() = EntryBudget.of(emptyList())

    private fun signer(clock: HlcClock) = EntrySigner(crypto, "group", clock.node.encodeToByteArray())

    private fun edit(current: SyncEntry, local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(id, EntryKind.EMAIL_FORWARDER, current, current.body, local, clock, signer(clock), budget)

    private fun create(local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(
            id,
            EntryKind.EMAIL_FORWARDER,
            current = null,
            applied = null,
            local,
            clock,
            signer(clock),
            budget,
        )

    @Test
    fun `a forwarder has a name part and a config part`() {
        val entry = create(forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "example.com"), clock("a"))
        assertEquals(
            setOf("/name", KeyguardPartSchemas.EMAIL_FORWARDER_CONFIG_PART, EntryVersion.REMAINDER),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `concurrent edits of the name and of the config both survive`() {
        val a = clock("a")
        val b = clock("b")
        val base = create(forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "example.com"), a)
        val renamed = edit(base, forwarder("Personal", "anonaddy", "apiKey" to "a", "domain" to "example.com"), a)
        val rekeyed = edit(base, forwarder("Work", "anonaddy", "apiKey" to "b", "domain" to "example.com"), b)

        val expected = forwarder("Personal", "anonaddy", "apiKey" to "b", "domain" to "example.com")
        assertEquals(expected, merger.merge(renamed, rekeyed).body)
        assertEquals(expected, merger.merge(rekeyed, renamed).body)
    }

    @Test
    fun `concurrent config edits never mix their keys`() {
        val a = clock("a")
        val b = clock("b")
        val base = create(forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "a.com"), a)
        val first = edit(base, forwarder("Work", "anonaddy", "apiKey" to "b", "domain" to "a.com"), a)
        val second = edit(base, forwarder("Work", "simplelogin", "apiKey" to "c"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer config wins whole: its service and all of its keys.
        assertEquals(forwarder("Work", "simplelogin", "apiKey" to "c"), merged.body)
    }
}
