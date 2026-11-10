package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinkUrlOverridesTest {
    companion object {
        /** The name of the override every app adds for itself. */
        private const val BUILT_IN = "HTTPS-ify"
    }

    @Test
    fun `linking keeps the built-in override on each device, once`() = runTest {
        val (a, b) = linkedPair {}
        a.exchangeWith(b)
        b.exchangeWith(a)

        assertEquals(listOf(BUILT_IN), a.urlOverrideList().map { it.name })
        assertEquals(listOf(BUILT_IN), b.urlOverrideList().map { it.name })
        assertNull(a.urlOverrideSyncId(BUILT_IN))
        assertTrue(a.entriesOf(EntryKind.URL_OVERRIDE).isEmpty())
        assertTrue(b.entriesOf(EntryKind.URL_OVERRIDE).isEmpty())
    }

    @Test
    fun `linked apps sync their overrides both ways`() = runTest {
        val (a, b) = linkedPair {
            a.addUrlOverride("Docs", enabled = false)
        }
        b.addUrlOverride("Wiki", regex = "^ftp:.*", command = "cmd://open {url}")
        a.exchangeWith(b)

        val names = setOf(BUILT_IN, "Docs", "Wiki")
        assertEquals(names, a.urlOverrideList().map { it.name }.toSet())
        assertEquals(names, b.urlOverrideList().map { it.name }.toSet())
        assertEquals(a.urlOverrideSyncId("Docs"), b.urlOverrideSyncId("Docs"))
        assertEquals(a.urlOverrideSyncId("Wiki"), b.urlOverrideSyncId("Wiki"))
        assertNotNull(a.urlOverrideSyncId("Wiki"))
        assertFalse(b.urlOverride("Docs").enabled)
        val wiki = a.urlOverride("Wiki")
        assertEquals("^ftp:.*", wiki.regex.pattern)
        assertEquals("cmd://open {url}", wiki.command)
        assertTrue(wiki.enabled)
    }

    @Test
    fun `an edit updates the override in place and a delete removes it`() = runTest {
        val (a, b) = linkedPair {
            a.addUrlOverride("Docs")
        }
        a.exchangeWith(b)
        val before = b.urlOverride("Docs")

        a.time += 1_000
        a.editUrlOverride(a.urlOverride("Docs"), name = "Manual", enabled = false)
        a.exchangeWith(b)

        val after = b.urlOverride("Manual")
        assertEquals(before.id, after.id)
        assertFalse(after.enabled)

        a.time += 1_000
        a.removeUrlOverride(a.urlOverride("Manual"))
        a.exchangeWith(b)

        assertEquals(listOf(BUILT_IN), b.urlOverrideList().map { it.name })
    }

    @Test
    fun `changes of the built-in override stay on its device`() = runTest {
        val (a, b) = linkedPair {}
        a.exchangeWith(b)

        a.time += 1_000
        a.editUrlOverride(a.urlOverride(BUILT_IN), name = "HTTPS", enabled = false)
        b.time += 1_000
        b.removeUrlOverride(b.urlOverride(BUILT_IN))
        a.exchangeWith(b)
        b.exchangeWith(a)

        assertTrue(a.entriesOf(EntryKind.URL_OVERRIDE).isEmpty())
        assertTrue(b.entriesOf(EntryKind.URL_OVERRIDE).isEmpty())
        assertEquals(listOf("HTTPS"), a.urlOverrideList().map { it.name })
        assertFalse(a.urlOverride("HTTPS").enabled)
        assertEquals(emptyList(), b.urlOverrideList())
    }

    @Test
    fun `linking identical overrides adopts one id and keeps the local rows`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addUrlOverride("Docs")
        b.addUrlOverride("Docs")
        val before = b.urlOverride("Docs")

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        assertEquals(listOf("Docs", BUILT_IN), a.urlOverrideList().map { it.name }.sorted())
        assertEquals(listOf("Docs", BUILT_IN), b.urlOverrideList().map { it.name }.sorted())
        assertEquals(a.urlOverrideSyncId("Docs"), b.urlOverrideSyncId("Docs"))
        assertEquals(before.id, b.urlOverride("Docs").id)
    }
}
