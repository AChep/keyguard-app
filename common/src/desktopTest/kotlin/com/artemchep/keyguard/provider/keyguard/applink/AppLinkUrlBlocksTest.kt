package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLinkUrlBlocksTest : AppSyncRowContractTest(UrlBlockTestRows) {
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
