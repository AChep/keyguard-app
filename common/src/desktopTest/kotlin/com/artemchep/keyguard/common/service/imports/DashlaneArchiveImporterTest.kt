package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.createZipService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/** Independently authored archives; no upstream fixtures are included. */
class DashlaneArchiveImporterTest {
    @Test
    fun `each archive table validates its own width and malformed headers are skipped`() = runTest {
        val result = parse(
            "credentials.csv" to "username,title,password,url\nu,Portal,p,https://site.test\nsecret fragment",
            "securenotes.csv" to "title,note\nNote,body\nBroken,body,extra",
            "broken.csv" to "title,note,\"",
        )
        val plan = assertIs<ImportResult.Success>(result).plan
        assertEquals(listOf("Portal", "Note"), plan.items.map { it.request.title })
        assertEquals(2, plan.skips[ImportSkipReason.MalformedRecord])
        assertTrue(plan.skips.titlesOf(ImportSkipReason.MalformedRecord).isEmpty())
        assertEquals(1, plan.skips[ImportSkipReason.File])
        assertEquals(mapOf("broken.csv" to 1), plan.skips.titlesOf(ImportSkipReason.File))
    }

    @Test
    fun `unrecognized files are counted by filename alongside valid items`() = runTest {
        val result = parse(
            "export/credentials.csv" to "username,title,password,url\nmorgan,Portal,pw,https://portal.example",
            "export/future.csv" to "new_schema,value\none,first\ntwo,second",
            "export/empty.csv" to "",
            "export/header-only.csv" to "new_schema,value",
            "__MACOSX/._future.csv" to "metadata",
            "export/._credentials.csv" to "metadata",
            "readme.txt" to "Archive documentation",
        )
        val plan = assertIs<ImportResult.Success>(result).plan
        assertEquals("Portal", plan.items.single().request.title)
        assertEquals(3, plan.skips[ImportSkipReason.File])
        assertEquals(3, plan.skips.totalCount)
        assertEquals(
            mapOf("export/future.csv" to 1, "export/empty.csv" to 1, "export/header-only.csv" to 1),
            plan.skips.titlesOf(ImportSkipReason.File),
        )
    }

    @Test
    fun `an entirely unrecognized archive still fails`() = runTest {
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parse("future.csv" to "new_schema,value\none,first"),
        )
    }

    @Test
    fun `recognized empty tables are not reported as skipped files`() = runTest {
        val result = parse("securenotes.csv" to "title,note,category")
        val plan = assertIs<ImportResult.Success>(result).plan
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.skips.isEmpty)
    }

    @Test
    fun `android and web files use their own null conventions in one archive`() = runTest {
        val result = parse(
            "android.csv" to "user_name,title,password,url,note\nnull,Android,null,,null",
            "web.csv" to "username,title,password,url,note\nnull,Web,null,,null",
            "securenotes.csv" to "title,note\nNote,null",
        )
        val plan = assertIs<ImportResult.Success>(result).plan
        val items = plan.items.associate { it.request.title to it.request }
        assertEquals(null, items.getValue("Android").login.username)
        assertEquals(null, items.getValue("Android").note)
        assertEquals("null", items.getValue("Web").login.username)
        assertEquals("null", items.getValue("Web").note)
        assertEquals("null", items.getValue("Note").note)
        assertTrue(plan.skips.isEmpty)
    }

    private suspend fun parse(vararg entries: Pair<String, String>): ImportResult {
        val buffer = Buffer()
        createZipService().zip(
            outputStream = buffer,
            config = ZipConfig(),
            entries = entries.map { (name, text) ->
                ZipEntry(
                    name = name,
                    data = ZipEntry.Data.In { Buffer().apply { writeString(text) } },
                )
            },
        )
        return importTestService().parse(
            format = ImportFormatId.Dashlane,
            input = BytesImportInput(buffer.readByteArray()),
            now = IMPORT_TEST_NOW,
        )
    }
}
