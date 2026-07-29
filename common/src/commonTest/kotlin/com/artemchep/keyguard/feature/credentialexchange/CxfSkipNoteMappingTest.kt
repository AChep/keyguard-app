package com.artemchep.keyguard.feature.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.CxfExportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.cxfExportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfImportSkips
import com.artemchep.keyguard.feature.credentialexchange.export.labelRes
import com.artemchep.keyguard.feature.credentialexchange.export.toNotes
import com.artemchep.keyguard.feature.credentialexchange.imports.labelRes
import com.artemchep.keyguard.feature.credentialexchange.imports.toNotes
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tally-to-warning-row wiring on both review screens: one row per counted
 * reason, in enum-declaration order — which is the order the user reads them in
 * — with a positive count and a concise label of its own.
 */
class CxfSkipNoteMappingTest {
    @Test
    fun `the import side turns every counted reason into exactly one row`() {
        val skips = cxfImportSkips(
            CxfImportSkipReason.Account to 4,
            CxfImportSkipReason.Otp to 2,
            CxfImportSkipReason.Item to 0,
            CxfImportSkipReason.Passkey to 1,
        )
        val notes = skips.toNotes()
        assertEquals(3, notes.size)
        assertEquals(listOf(1, 2, 4), notes.map { it.count })
        assertTrue(cxfImportSkips().toNotes().isEmpty())
    }

    @Test
    fun `the export side turns every counted reason into exactly one row`() {
        val skips = cxfExportSkips(
            CxfExportSkipReason.Item to 3,
            CxfExportSkipReason.SshKey to 1,
        )
        val notes = skips.toNotes()
        assertEquals(2, notes.size)
        assertEquals(listOf(1, 3), notes.map { it.count })
        assertTrue(cxfExportSkips().toNotes().isEmpty())
    }

    @Test
    fun `an attributed reason becomes an expandable row listing its items`() {
        val skips = cxfExportSkips(CxfExportSkipReason.Attachment to 3).titled("Netflix") +
                cxfExportSkips(CxfExportSkipReason.Attachment to 1).titled("GitHub")
        val note = skips.toNotes().single()
        assertEquals("Attachment", note.id)
        assertEquals(4, note.count)
        assertEquals(
            listOf("Netflix" to 3, "GitHub" to 1),
            note.titles.map { it.text to it.count },
        )
        assertNotNull(note.onToggle)
        assertEquals(0, note.remainingCount)
    }

    @Test
    fun `a reason that attributed nothing offers no toggle`() {
        // Renders exactly as it did before notes could expand — no chevron on a
        // row that would open onto nothing.
        val note = cxfImportSkips(CxfImportSkipReason.Account to 2).toNotes().single()
        assertTrue(note.titles.isEmpty())
        assertNull(note.onToggle)
        assertFalse(note.expanded)
    }

    @Test
    fun `titles that fall short of the count leave a remainder`() {
        // Two items lost an item-level skip; only one of them had a readable
        // name. The row must not let one title imply it is the whole story.
        val skips = cxfImportSkips(CxfImportSkipReason.Item to 1).titled("Netflix") +
                cxfImportSkips(CxfImportSkipReason.Item to 2)
        val note = skips.toNotes().single()
        assertEquals(3, note.count)
        assertEquals(listOf("Netflix"), note.titles.map { it.text })
        assertEquals(2, note.remainingCount)
    }

    @Test
    fun `only the opened note reports itself expanded`() {
        val skips = cxfImportSkips(
            CxfImportSkipReason.Passkey to 1,
            CxfImportSkipReason.Otp to 1,
        ).titled("Netflix")
        val toggled = mutableListOf<String>()
        val notes = skips.toNotes(
            expandedIds = setOf(CxfImportSkipReason.Otp.name),
            onToggle = toggled::add,
        )
        assertEquals(
            listOf(false, true),
            notes.map { it.expanded },
            "expansion follows the id, not the position",
        )
        notes.first().onToggle?.invoke()
        assertEquals(listOf(CxfImportSkipReason.Passkey.name), toggled)
    }

    @Test
    fun `reasons use generic labels shared across import and export`() {
        assertEquals(
            listOf(
                Res.plurals.skipped_passkeys_note,
                Res.plurals.skipped_otp_note,
                Res.plurals.skipped_ssh_keys_note,
                Res.plurals.skipped_unsupported_credentials_note,
                Res.plurals.skipped_duplicate_credentials_note,
                Res.plurals.skipped_items_note,
                Res.plurals.skipped_folders_note,
                Res.plurals.skipped_accounts_note,
            ),
            CxfImportSkipReason.entries.map { it.labelRes() },
        )
        assertEquals(
            listOf(
                Res.plurals.skipped_passkeys_note,
                Res.plurals.skipped_otp_note,
                Res.plurals.skipped_ssh_keys_note,
                Res.plurals.skipped_gpg_keys_note,
                Res.plurals.skipped_attachments_note,
                Res.plurals.skipped_password_history_note,
                Res.plurals.skipped_archived_items_note,
                Res.plurals.skipped_items_note,
                Res.plurals.skipped_accounts_note,
            ),
            CxfExportSkipReason.entries.map { it.labelRes() },
        )
    }
}
