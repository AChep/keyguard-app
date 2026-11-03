package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.service.imports.impl.IMPORT_MAX_FOLDER_DEPTH
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.ImportTooLargeException
import com.artemchep.keyguard.common.service.imports.impl.readBytes
import com.artemchep.keyguard.common.service.imports.mapping.ImportedTotp
import com.artemchep.keyguard.common.service.imports.mapping.importedTitleFromUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedTotp
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImportMappingTest {
    @Test
    fun `a file over the limit is too large`() {
        val input = BytesImportInput(ByteArray(11))
        assertEquals(11, input.readBytes(maxBytes = 11).size)
        assertFailsWith<ImportTooLargeException> {
            input.readBytes(maxBytes = 10)
        }
    }

    @Test
    fun `one-time passwords are read from uris and bare secrets`() {
        assertEquals(ImportedTotp.Absent, importedTotp(null))
        assertEquals(ImportedTotp.Absent, importedTotp("  "))
        assertEquals(
            ImportedTotp.Valid("otpauth://totp/A?secret=JBSWY3DPEHPK3PXP"),
            importedTotp(" otpauth://totp/A?secret=JBSWY3DPEHPK3PXP "),
        )
        assertEquals(ImportedTotp.Valid("steam://JBSWY3DPEHPK3PXP"), importedTotp("steam://JBSWY3DPEHPK3PXP"))
        // A bare secret is canonicalized: separators dropped, upper-cased.
        assertEquals(ImportedTotp.Valid("JBSWY3DPEHPK3PXP"), importedTotp("jbsw y3dp-ehpk 3pxp"))
        assertEquals(ImportedTotp.Invalid, importedTotp("not base32!"))
        assertEquals(ImportedTotp.Invalid, importedTotp("otpauth://totp/A"))
    }

    @Test
    fun `uris are trimmed and blank ones dropped`() {
        assertNull(importedUriOrNull(null))
        assertNull(importedUriOrNull(" "))
        assertEquals("https://a.test", importedUriOrNull(" https://a.test ")?.uri)
    }

    @Test
    fun `a chromium android uri without a valid hash keeps the package`() {
        val uri = importedUriOrNull("android://not-a-hash@com.example/")
        assertEquals("androidapp://com.example", uri?.uri)
        assertEquals(emptyList(), uri?.signatures)
    }

    @Test
    fun `titles come from the host of web addresses only`() {
        assertEquals("example.com", importedTitleFromUriOrNull("https://www.example.com/path?q=1"))
        assertNull(importedTitleFromUriOrNull("androidapp://com.example"))
        assertNull(importedTitleFromUriOrNull(null))
    }

    @Test
    fun `folder paths share their ancestors`() {
        val plan = ImportPlanBuilder(ImportPlan.Source())
        val a = plan.folder(listOf("A"))
        val ab = plan.folder(listOf("A", " B ", ""))
        val ac = plan.folder(listOf("A", "C"))
        assertNull(plan.folder(listOf(" ", "")))
        assertEquals(a, plan.folder(listOf("A")))

        val folders = plan.build().folders
        assertEquals(listOf("A", "B", "C"), folders.map { it.title })
        assertEquals(listOf(null, a, a), folders.map { it.parentKey })
        assertEquals(listOf(a, ab, ac), folders.map { it.key })
    }

    @Test
    fun `deep folder paths are cut at the depth limit`() {
        val plan = ImportPlanBuilder(ImportPlan.Source())
        val path = (1..IMPORT_MAX_FOLDER_DEPTH + 5).map { "f$it" }
        plan.folder(path)

        val folders = plan.build().folders
        assertEquals(IMPORT_MAX_FOLDER_DEPTH, folders.size)
        assertEquals(
            path.drop(IMPORT_MAX_FOLDER_DEPTH - 1).joinToString("/"),
            folders.last().title,
        )
    }
}
