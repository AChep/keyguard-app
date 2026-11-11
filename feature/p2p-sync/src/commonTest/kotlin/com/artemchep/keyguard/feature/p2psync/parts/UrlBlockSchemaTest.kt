package com.artemchep.keyguard.feature.p2psync.parts

import com.artemchep.keyguard.feature.p2psync.merge.EntryMerger
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.testing.EntryEdits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlBlockSchemaTest {
    private val edits = EntryEdits(EntryKind.URL_BLOCK)
    private val merger = EntryMerger

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

    @Test
    fun `a blocked URI has a name a description a rule an enabled and an exposed part`() {
        val entry = edits.create(block("Bank", "Never fill", "example.com", "domain"), edits.clock("a"))
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
    fun `concurrent edits of the name the description the rule and the switches all survive`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val c = edits.clock("c")
        val d = edits.clock("d")
        val e = edits.clock("e")
        val base = edits.create(block("Bank", "Never fill", "example.com", "domain"), a)
        val renamed = edits.edit(base, block("Shop", "Never fill", "example.com", "domain"), a)
        val described = edits.edit(base, block("Bank", "Fill by hand", "example.com", "domain"), b)
        val reruled = edits.edit(base, block("Bank", "Never fill", "login.example.com", "host"), c)
        val disabled = edits.edit(base, block("Bank", "Never fill", "example.com", "domain", enabled = false), d)
        val hidden = edits.edit(base, block("Bank", "Never fill", "example.com", "domain", exposed = false), e)

        val expected = block("Shop", "Fill by hand", "login.example.com", "host", enabled = false, exposed = false)
        val edits = listOf(renamed, described, reruled, disabled, hidden)
        assertEquals(expected, edits.reduce(merger::merge).body)
        assertEquals(expected, edits.reversed().reduce(merger::merge).body)
    }

    @Test
    fun `a concurrent mode edit never applies to the URI of another rule`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val base = edits.create(block("Bank", "Never fill", "example.com", "domain"), a)
        val first = edits.edit(base, block("Bank", "Never fill", "^https://bank\\.example\\.com/", "regex"), a)
        val second = edits.edit(base, block("Bank", "Never fill", "example.com", "host"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer rule wins whole: its URI and its mode.
        assertEquals(block("Bank", "Never fill", "example.com", "host"), merged.body)
    }
}
