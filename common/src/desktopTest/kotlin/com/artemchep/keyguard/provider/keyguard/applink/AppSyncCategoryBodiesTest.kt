package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fields of a record are named twice: by the parts of its kind, which merge them, and
 * by its category, which reads and writes them. A field only one of them names still
 * syncs, but merges with the rest of the record instead of as its own part. So it is a
 * mistake, unless its kind says it merges so, see [remainderFields].
 */
class AppSyncCategoryBodiesTest {
    /**
     * The optional fields that merge with the rest of the record on purpose, by kind: the
     * builds before them compose the versions as the builds after them do.
     */
    private val remainderFields = mapOf(
        // See KeyguardPartSchemas.urlBlock.
        EntryKind.URL_BLOCK to setOf("scope"),
    )

    @Test
    fun `the body of a record has the fields the parts of its kind name and no other`() = runTest {
        AppLinkTestDevice("A").use { a ->
            a.addForwarder("Work", "simplelogin", "apiKey" to "key")
            a.addWordlist("Animals", listOf("cat", "dog"))
            a.addUrlOverride("Docs")
            a.addUrlBlock("Bank")
            a.addUrlBlock("Shop", scope = UrlBlockScope.SAVE)
            a.addPrivilegedApp("com.example.browser")
            val categories = AppSyncCategories.default(
                hashOf = { bytes -> BlobExchange.hashOf(a.crypto, bytes) },
                received = AppLinkReceivedBlobs(),
            )

            val read = mutableSetOf<Pair<EntryKind, String>>()
            categories.all.forEach { category ->
                val kind = category.kind
                val ids = category.readIds(a.db)
                assertTrue(ids.isNotEmpty(), "Add a record of the kind ${kind.wireName} to this test.")
                val named = kind.schema.parts
                    .flatMap { part -> part.paths }
                    .mapTo(mutableSetOf()) { path -> path.segments.first() }
                val remainder = remainderFields[kind].orEmpty()
                ids.forEach { id ->
                    val keys = requireNotNull(category.read(a.db, id)).keys
                    keys.filter { it in remainder }.mapTo(read) { kind to it }
                    assertEquals(named, keys - remainder, kind.wireName)
                }
            }
            // Each field that merges with the rest of the record is in a record of this test.
            val expected = remainderFields.flatMap { (kind, fields) -> fields.map { kind to it } }.toSet()
            assertEquals(expected, read)
        }
    }
}
