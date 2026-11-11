package com.artemchep.keyguard.feature.p2psync.parts

import com.artemchep.keyguard.feature.p2psync.merge.EntryMerger
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.testing.EntryEdits
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies.forwarder
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailForwarderSchemaTest {
    private val edits = EntryEdits(EntryKind.EMAIL_FORWARDER)
    private val merger = EntryMerger

    @Test
    fun `a forwarder has a name part and a config part`() {
        val body = forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "example.com")
        val entry = edits.create(body, edits.clock("a"))
        assertEquals(
            setOf("/name", KeyguardPartSchemas.EMAIL_FORWARDER_CONFIG_PART, EntryVersion.REMAINDER),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `concurrent edits of the name and of the config both survive`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val base = edits.create(forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "example.com"), a)
        val renamed = edits.edit(base, forwarder("Personal", "anonaddy", "apiKey" to "a", "domain" to "example.com"), a)
        val rekeyed = edits.edit(base, forwarder("Work", "anonaddy", "apiKey" to "b", "domain" to "example.com"), b)

        val expected = forwarder("Personal", "anonaddy", "apiKey" to "b", "domain" to "example.com")
        assertEquals(expected, merger.merge(renamed, rekeyed).body)
        assertEquals(expected, merger.merge(rekeyed, renamed).body)
    }

    @Test
    fun `concurrent config edits never mix their keys`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val base = edits.create(forwarder("Work", "anonaddy", "apiKey" to "a", "domain" to "a.com"), a)
        val first = edits.edit(base, forwarder("Work", "anonaddy", "apiKey" to "b", "domain" to "a.com"), a)
        val second = edits.edit(base, forwarder("Work", "simplelogin", "apiKey" to "c"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer config wins whole: its service and all of its keys.
        assertEquals(forwarder("Work", "simplelogin", "apiKey" to "c"), merged.body)
    }
}
