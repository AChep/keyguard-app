package com.artemchep.keyguard.common.service.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CsvImportValidationTest {
    @Test
    fun `short and long rows are skipped across every header mapping path`() = runTest {
        val fixtures = mapOf(
            ImportFormatId.Bitwarden to (
                "folder,type,name,login_uri,login_username,login_password,notes" to
                    "Personal,login,Site,https://site.test,u,p,"
                ),
            ImportFormatId.ProtonPass to (
                "vault,type,name,url,username,password,note" to
                    "Personal,login,Site,https://site.test,u,p,"
                ),
            ImportFormatId.Dashlane to (
                "category,type,title,url,username,password,note" to
                    "Personal,login,Site,https://site.test,u,p,"
                ),
        )
        fixtures.forEach { (format, fixture) ->
            val (header, valid) = fixture
            val plan = parseImportPlan(
                format,
                listOf(
                    header,
                    valid,
                    valid.replace("Personal", "Short").dropLast(1),
                    valid.replace("Personal", "Long") + "unexpected,extra",
                    "secret fragment",
                    valid.replace("Site", "After"),
                ).joinToString("\n"),
            )
            plan.assertVaultStorable()
            assertEquals(listOf("Site", "After"), plan.items.map { it.request.title }, format.name)
            assertTrue(plan.folders.none { it.title == "Short" || it.title == "Long" })
            assertEquals(importSkips(ImportSkipReason.MalformedRecord to 3), plan.skips)
            assertTrue(plan.skips.titlesOf(ImportSkipReason.MalformedRecord).isEmpty())
        }
    }

    @Test
    fun `unquoted multiline proton notes do not create fragment items`() = runTest {
        val plan = parseImportPlan(
            ImportFormatId.ProtonPass,
            """
                type,name,url,username,password,note,vault
                login,Broken,https://site.test,u,p,first line
                2 line
                last line,Lost vault
                login,Good,https://site.test,u,p,"first line
                2 line
                last line",Personal
            """.trimIndent(),
        )
        assertEquals("Good", plan.items.single().request.title)
        assertEquals("first line\n2 line\nlast line", plan.items.single().request.note)
        assertEquals(listOf("Personal"), plan.folders.map { it.title })
        assertEquals(importSkips(ImportSkipReason.MalformedRecord to 3), plan.skips)
    }

    @Test
    fun `width includes optional unknown and duplicate header columns`() = runTest {
        val plan = parseImportPlan(
            ImportFormatId.Chromium,
            "name,url,username,password,note,future,NAME\n" +
                "Good,https://site.test,u,p,,,Alias\n" +
                "Short,https://site.test,u,p,,\n" +
                "Required only,https://site.test,u,p\n",
        )
        assertEquals("Good", plan.items.single().request.title)
        assertEquals(importSkips(ImportSkipReason.MalformedRecord to 2), plan.skips)
    }

    @Test
    fun `quoted delimiters multiline values and trailing empty fields remain valid`() = runTest {
        listOf(',', ';', '\t').forEach { delimiter ->
            listOf("\n", "\r\n", "\r").forEach { newline ->
                val header = listOf("name", "url", "username", "password", "note", "extra")
                    .joinToString(delimiter.toString())
                val fields = listOf(
                    "Good", "https://site.test", "u", "\" p\"\"${delimiter}w \"", "\"a${newline}b\"", "",
                ).joinToString(delimiter.toString())
                val plan = parseImportPlan(ImportFormatId.Chromium, "\uFEFF$header$newline$fields")
                val item = plan.items.single().request
                assertEquals(" p\"${delimiter}w ", item.login.password)
                assertEquals("a${newline}b", item.note)
                assertTrue(plan.skips.isEmpty)
            }
        }
    }

    @Test
    fun `malformed headers fail even when required names are present`() = runTest {
        listOf(
            ImportFormatId.Chromium to "name,url,username,password,\"",
            ImportFormatId.ProtonPass to "type,name,url,username,password,note,\"",
            ImportFormatId.Dashlane to "username,title,password,url,\"",
        ).forEach { (format, text) ->
            assertEquals(ImportResult.Failure(ImportError.Parse), parseImport(format, text))
        }
    }

    @Test
    fun `blank unterminated quotes are counted even with no valid items`() = runTest {
        val plan = parseImportPlan(ImportFormatId.Chromium, "name,url,username,password\n\"")
        assertTrue(plan.items.isEmpty())
        assertEquals(importSkips(ImportSkipReason.MalformedRecord to 1), plan.skips)
    }

    @Test
    fun `keeper retains variable width records and counts broken quotes without titles`() = runTest {
        val plan = parseImportPlan(
            ImportFormatId.Keeper,
            "Personal,Plain,u,p,https://site.test,\n" +
                "Personal,Custom,u,p,https://site.test,,,label,value\n" +
                "Personal,secret fragment,u,\"",
        )
        assertEquals(listOf("Plain", "Custom"), plan.items.map { it.request.title })
        assertEquals("value", plan.items.last().request.fields.single().value)
        assertEquals(importSkips(ImportSkipReason.MalformedRecord to 1), plan.skips)
        assertTrue(plan.skips.titlesOf(ImportSkipReason.MalformedRecord).isEmpty())
    }
}
