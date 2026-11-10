package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLinkUrlBlocksTest {
    @Test
    fun `linked apps sync their rules both ways`() = runTest {
        val (a, b) = linkedPair {
            a.addUrlBlock(
                name = "Bank",
                description = "Fills by hand",
                uri = "^https://bank\\.example/.*",
                mode = MatchDetection.RegularExpression,
                enabled = false,
                exposed = false,
            )
        }
        b.addUrlBlock("Shop", uri = "shop.example", mode = MatchDetection.Host)
        a.exchangeWith(b)

        val names = setOf("Bank", "Shop")
        assertEquals(names, a.urlBlockList().map { it.name }.toSet())
        assertEquals(names, b.urlBlockList().map { it.name }.toSet())
        assertEquals(a.urlBlockSyncId("Bank"), b.urlBlockSyncId("Bank"))
        assertEquals(a.urlBlockSyncId("Shop"), b.urlBlockSyncId("Shop"))
        val bank = b.urlBlock("Bank")
        assertEquals("Fills by hand", bank.description)
        assertEquals("^https://bank\\.example/.*", bank.uri)
        assertEquals(MatchDetection.RegularExpression, bank.mode)
        assertFalse(bank.enabled)
        assertFalse(bank.exposed)
        val shop = a.urlBlock("Shop")
        assertEquals("shop.example", shop.uri)
        assertEquals(MatchDetection.Host, shop.mode)
        assertTrue(shop.enabled)
        assertTrue(shop.exposed)
    }

    @Test
    fun `an edit updates the rule in place and a delete removes it`() = runTest {
        val (a, b) = linkedPair {
            a.addUrlBlock("Bank")
        }
        a.exchangeWith(b)
        val before = b.urlBlock("Bank")

        a.time += 1_000
        a.editUrlBlock(
            a.urlBlock("Bank"),
            name = "Shop",
            uri = "shop.example/cart",
            mode = MatchDetection.StartsWith,
            enabled = false,
        )
        a.exchangeWith(b)

        val after = b.urlBlock("Shop")
        assertEquals(before.id, after.id)
        assertEquals("shop.example/cart", after.uri)
        assertEquals(MatchDetection.StartsWith, after.mode)
        assertFalse(after.enabled)

        a.time += 1_000
        a.removeUrlBlock(a.urlBlock("Shop"))
        a.exchangeWith(b)

        assertEquals(emptyList(), b.urlBlockList())
    }

    @Test
    fun `linking identical rules adopts one id and keeps the local rows`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addUrlBlock("Bank")
        b.addUrlBlock("Bank")
        val before = b.urlBlock("Bank")

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        assertEquals(listOf("Bank"), a.urlBlockList().map { it.name })
        assertEquals(listOf("Bank"), b.urlBlockList().map { it.name })
        assertEquals(a.urlBlockSyncId("Bank"), b.urlBlockSyncId("Bank"))
        assertEquals(before.id, b.urlBlock("Bank").id)
    }

    @Test
    fun `a local edit is recorded and only an exposed rule is copied to the exposed database`() = runTest {
        val (a) = linkedPair {}
        a.addUrlBlock("Shown", exposed = true)
        a.addUrlBlock("Hidden", exposed = false)

        assertEquals(
            setOf(a.urlBlockSyncId("Shown"), a.urlBlockSyncId("Hidden")),
            a.entriesOf(EntryKind.URL_BLOCK).map { it.id }.toSet(),
        )
        assertEquals(mapOf(a.urlBlockId("Shown") to "Shown"), a.exposedUrlBlocks())

        // The copy follows the switch.
        a.time += 1_000
        a.editUrlBlock(a.urlBlock("Shown"), exposed = false)
        a.editUrlBlock(a.urlBlock("Hidden"), exposed = true)
        assertEquals(mapOf(a.urlBlockId("Hidden") to "Hidden"), a.exposedUrlBlocks())

        a.time += 1_000
        a.removeUrlBlock(a.urlBlock("Hidden"))
        assertEquals(emptyMap(), a.exposedUrlBlocks())
    }

    private suspend fun AppLinkTestDevice.urlBlockId(name: String): String = requireNotNull(urlBlock(name).id)
}
