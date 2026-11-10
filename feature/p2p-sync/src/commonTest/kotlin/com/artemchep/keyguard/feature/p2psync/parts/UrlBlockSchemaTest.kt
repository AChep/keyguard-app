package com.artemchep.keyguard.feature.p2psync.parts

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntryMerger
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlBlockSchemaTest {
    private val publisher = LocalEditPublisher()
    private val merger = EntryMerger
    private val id = "5d2e8a41-7c3b-4f6a-b1e9-0a8d7c6b5e4f"
    private var time = 1_000_000L

    private val crypto = TestCrypto()

    private fun clock(node: String) = HlcClock(node = node, physicalNow = { time++ }, persist = {})

    private val budget get() = EntryBudget.of(emptyList())

    private fun signer(clock: HlcClock) = EntrySigner(crypto, "group", clock.node.encodeToByteArray())

    private fun block(
        name: String,
        description: String,
        uri: String,
        mode: String,
        enabled: Boolean = true,
        exposed: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("uri", uri)
        put("mode", mode)
        put("enabled", enabled)
        put("exposed", exposed)
    }

    private fun edit(current: SyncEntry, local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(id, EntryKind.URL_BLOCK, current, current.body, local, clock, signer(clock), budget)

    private fun create(local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(
            id,
            EntryKind.URL_BLOCK,
            current = null,
            applied = null,
            local,
            clock,
            signer(clock),
            budget,
        )

    @Test
    fun `a blocked URI has a name, a description, a rule, an enabled and an exposed part`() {
        val entry = create(block("Bank", "Never fill", "example.com", "domain"), clock("a"))
        assertEquals(
            setOf(
                "/name",
                "/description",
                KeyguardPartSchemas.URL_BLOCK_RULE_PART,
                "/enabled",
                "/exposed",
                EntryVersion.REMAINDER,
            ),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `concurrent edits of the name, the description, the rule and the switches all survive`() {
        val a = clock("a")
        val b = clock("b")
        val c = clock("c")
        val d = clock("d")
        val e = clock("e")
        val base = create(block("Bank", "Never fill", "example.com", "domain"), a)
        val renamed = edit(base, block("Shop", "Never fill", "example.com", "domain"), a)
        val described = edit(base, block("Bank", "Fill by hand", "example.com", "domain"), b)
        val reruled = edit(base, block("Bank", "Never fill", "login.example.com", "host"), c)
        val disabled = edit(base, block("Bank", "Never fill", "example.com", "domain", enabled = false), d)
        val hidden = edit(base, block("Bank", "Never fill", "example.com", "domain", exposed = false), e)

        val expected = block("Shop", "Fill by hand", "login.example.com", "host", enabled = false, exposed = false)
        val edits = listOf(renamed, described, reruled, disabled, hidden)
        assertEquals(expected, edits.reduce(merger::merge).body)
        assertEquals(expected, edits.reversed().reduce(merger::merge).body)
    }

    @Test
    fun `a concurrent mode edit never applies to the URI of another rule`() {
        val a = clock("a")
        val b = clock("b")
        val base = create(block("Bank", "Never fill", "example.com", "domain"), a)
        val first = edit(base, block("Bank", "Never fill", "^https://bank\\.example\\.com/", "regex"), a)
        val second = edit(base, block("Bank", "Never fill", "example.com", "host"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer rule wins whole: its URI and its mode.
        assertEquals(block("Bank", "Never fill", "example.com", "host"), merged.body)
    }
}
