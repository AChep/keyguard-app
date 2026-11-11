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

class UrlOverrideSchemaTest {
    private val edits = EntryEdits(EntryKind.URL_OVERRIDE)
    private val merger = EntryMerger

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

    @Test
    fun `an override has a name part a rule part and an enabled part`() {
        val entry = edits.create(override("Docs", "^http:.*", "https://{url:rmvscm}"), edits.clock("a"))
        assertEquals(
            setOf("/name", KeyguardPartSchemas.URL_OVERRIDE_RULE_PART, "/enabled", EntryVersion.REMAINDER),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `concurrent edits of the name the rule and the switch all survive`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val c = edits.clock("c")
        val base = edits.create(override("Docs", "^http:.*", "https://{url:rmvscm}"), a)
        val renamed = edits.edit(base, override("Wiki", "^http:.*", "https://{url:rmvscm}"), a)
        val reruled = edits.edit(base, override("Docs", "^ftp:.*", "sftp://{url:rmvscm}"), b)
        val disabled = edits.edit(base, override("Docs", "^http:.*", "https://{url:rmvscm}", enabled = false), c)

        val expected = override("Wiki", "^ftp:.*", "sftp://{url:rmvscm}", enabled = false)
        assertEquals(expected, merger.merge(merger.merge(renamed, reruled), disabled).body)
        assertEquals(expected, merger.merge(disabled, merger.merge(reruled, renamed)).body)
    }

    @Test
    fun `concurrent rule edits never mix a regex with another command`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val base = edits.create(override("Docs", "^http:.*", "https://{url:rmvscm}"), a)
        val first = edits.edit(base, override("Docs", "^ftp:.*", "https://{url:rmvscm}"), a)
        val second = edits.edit(base, override("Docs", "^http:.*", "cmd://open {url}"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer rule wins whole: its regex and its command.
        assertEquals(override("Docs", "^http:.*", "cmd://open {url}"), merged.body)
    }
}
