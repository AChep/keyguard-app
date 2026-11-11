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

class PrivilegedAppSchemaTest {
    private val edits = EntryEdits(EntryKind.PRIVILEGED_APP)
    private val merger = EntryMerger

    private fun app(packageName: String, fingerprint: String): JsonObject = buildJsonObject {
        put("packageName", packageName)
        put("certFingerprintSha256", fingerprint)
    }

    @Test
    fun `a privileged app has one part for its package and its fingerprint`() {
        val entry = edits.create(app("com.example.browser", "AA"), edits.clock("a"))
        // The key of the part travels between builds: it never changes.
        assertEquals(
            setOf("/packageName", EntryVersion.REMAINDER),
            entry.versions.single().parts.keys,
        )
    }

    @Test
    fun `a concurrent edit never applies its fingerprint to the package of another`() {
        val a = edits.clock("a")
        val b = edits.clock("b")
        val base = edits.create(app("com.example.browser", "AA"), a)
        val first = edits.edit(base, app("com.example.browser", "BB"), a)
        val second = edits.edit(base, app("org.example.other", "AA"), b)

        val merged = merger.merge(first, second)
        assertEquals(merged, merger.merge(second, first))
        // The newer app wins whole: its package and its fingerprint.
        assertEquals(app("org.example.other", "AA"), merged.body)
    }
}
