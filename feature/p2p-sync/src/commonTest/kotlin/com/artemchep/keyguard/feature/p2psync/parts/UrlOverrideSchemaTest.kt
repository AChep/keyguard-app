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

class UrlOverrideSchemaTest {
    private val publisher = LocalEditPublisher()
    private val merger = EntryMerger
    private val id = "0b6c1f8e-2d3a-4e5f-9a7b-8c6d5e4f3a2b"
    private var time = 1_000_000L

    private val crypto = TestCrypto()

    private fun clock(node: String) = HlcClock(node = node, physicalNow = { time++ }, persist = {})

    private val budget get() = EntryBudget.of(emptyList())

    private fun signer(clock: HlcClock) = EntrySigner(crypto, "group", clock.node.encodeToByteArray())

    private fun override(
        name: String,
        regex: String,
        command: String,
        enabled: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("regex", regex)
        put("command", command)
        put("enabled", enabled)
    }

    private fun edit(current: SyncEntry, local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(id, EntryKind.URL_OVERRIDE, current, current.body, local, clock, signer(clock), budget)

    private fun create(local: JsonObject, clock: HlcClock): SyncEntry =
        publisher.publish(
            id,
            EntryKind.URL_OVERRIDE,
            current = null,
            applied = null,
            local,
            clock,
            signer(clock),
            budget,
        )

    @Test
    fun `an override has a name part, a rule part and an enabled part`() {
        val entry = create(override("Docs", "^http:.*", "https://{url:rmvscm}"), clock("a"))
        assertEquals(
            setOf("/name", KeyguardPartSchemas.URL_OVERRIDE_RULE_PART, "/enabled", EntryVersion.REMAINDER),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `concurrent edits of the name, the rule and the switch all survive`() {
        val a = clock("a")
        val b = clock("b")
        val c = clock("c")
        val base = create(override("Docs", "^http:.*", "https://{url:rmvscm}"), a)
        val renamed = edit(base, override("Wiki", "^http:.*", "https://{url:rmvscm}"), a)
        val reruled = edit(base, override("Docs", "^ftp:.*", "sftp://{url:rmvscm}"), b)
        val disabled = edit(base, override("Docs", "^http:.*", "https://{url:rmvscm}", enabled = false), c)

        val expected = override("Wiki", "^ftp:.*", "sftp://{url:rmvscm}", enabled = false)
        assertEquals(expected, merger.merge(merger.merge(renamed, reruled), disabled).body)
        assertEquals(expected, merger.merge(disabled, merger.merge(reruled, renamed)).body)
    }

    @Test
    fun `concurrent rule edits never mix a regex with another command`() {
        val a = clock("a")
        val b = clock("b")
        val base = create(override("Docs", "^http:.*", "https://{url:rmvscm}"), a)
        val first = edit(base, override("Docs", "^ftp:.*", "https://{url:rmvscm}"), a)
        val second = edit(base, override("Docs", "^http:.*", "cmd://open {url}"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer rule wins whole: its regex and its command.
        assertEquals(override("Docs", "^http:.*", "cmd://open {url}"), merged.body)
    }
}
