package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinkUrlOverridesTest : AppSyncRowContractTest(UrlOverrideTestRows) {
    private companion object {
        /** The name of the override every app adds for itself. */
        const val BUILT_IN = "HTTPS-ify"
    }

    @Test
    fun `linking keeps the built-in override on each device once`() = runTest {
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
}
