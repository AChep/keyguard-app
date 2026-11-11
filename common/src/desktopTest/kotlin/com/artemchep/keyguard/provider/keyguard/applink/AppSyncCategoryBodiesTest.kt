package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fields of a record are named twice: by the parts of its kind, which merge them, and
 * by its category, which reads and writes them. A field only one of them names still
 * syncs, but merges with the rest of the record instead of as its own part.
 */
class AppSyncCategoryBodiesTest {
    @Test
    fun `the body of a record has the fields the parts of its kind name and no other`() = runTest {
        AppLinkTestDevice("A").use { a ->
            a.addForwarder("Work", "simplelogin", "apiKey" to "key")
            a.addWordlist("Animals", listOf("cat", "dog"))
            a.addUrlOverride("Docs")
            a.addUrlBlock("Bank")
            a.addPrivilegedApp("com.example.browser")
            val categories = AppSyncCategories.default(
                hashOf = { bytes -> BlobExchange.hashOf(a.crypto, bytes) },
                received = AppLinkReceivedBlobs(),
            )

            categories.all.forEach { category ->
                val kind = category.kind
                val ids = category.readIds(a.db)
                assertTrue(ids.isNotEmpty(), "Add a record of the kind ${kind.wireName} to this test.")
                val named = kind.schema.parts
                    .flatMap { part -> part.paths }
                    .mapTo(mutableSetOf()) { path -> path.segments.first() }
                ids.forEach { id ->
                    assertEquals(named, category.read(a.db, id)?.keys, kind.wireName)
                }
            }
        }
    }
}
