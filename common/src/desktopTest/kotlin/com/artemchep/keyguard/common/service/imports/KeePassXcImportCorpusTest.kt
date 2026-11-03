package com.artemchep.keyguard.common.service.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The KeePassXC file of the [ImportCorpus], written by `keepassxc-cli` from
 * the seed database.
 */
class KeePassXcImportCorpusTest {
    @Test
    fun `csv drops the root group and keeps the recycle bin as a folder`() = runTest {
        val plan = ImportCorpus.successPlan("keepassxc-csv")
        val folderByTitle = plan.folders.associateBy { it.title }
        assertEquals(setOf("Banking", "Cards", "Email", "Recycle Bin"), folderByTitle.keys)
        assertEquals(folderByTitle.getValue("Banking").key, folderByTitle.getValue("Cards").parentKey)
        assertNull(folderByTitle.getValue("Email").parentKey)
        val byUsername = plan.items.associateBy { it.request.login.username }

        assertNull(byUsername.getValue("top-user").folderKey)
        // KeePassXC lets a title be empty; the importer keeps it empty.
        assertNull(byUsername.getValue("untitled-user").request.title)
        // Custom attributes and the history are not in the CSV.
        with(byUsername.getValue("extras-user").request) {
            assertEquals("new-password", login.password)
            assertTrue(fields.isEmpty())
        }

        val bank = byUsername.getValue("bank-user")
        assertEquals(folderByTitle.getValue("Cards").key, bank.folderKey)
        assertEquals("Line one\nLine two", bank.request.note)
        assertEquals(
            "otpauth://totp/Bank:bank-user?secret=JBSWY3DPEHPK3PXP&period=30&digits=6&issuer=Bank",
            bank.request.login.totp,
        )

        with(byUsername.getValue("alice@exämple.com").request) {
            assertEquals("Mail, \"quoted\"", title)
            assertEquals(" spaced \"and\" quoted, ", login.password)
            assertEquals("Ünïcödé — 日本語", note)
        }
        // KeePassXC writes the recycle bin as an ordinary group.
        assertEquals(
            folderByTitle.getValue("Recycle Bin").key,
            byUsername.getValue("deleted-user").folderKey,
        )
    }
}
