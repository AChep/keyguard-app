package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class OnePasswordPuxUrlTest {
    @Test
    fun `1password preserves URL match modes and a separate primary URL`() = runTest {
        val archive = zip(
            "export.data" to """
                {"accounts": [{"vaults": [{"items": [{
                  "categoryUuid": "001", "overview": {
                    "title": "URL modes", "url": "https://primary.example.org/",
                    "urls": [
                      {"url": "https://host.example.org/", "mode": "host"},
                      {"url": "https://never.example.org/", "mode": "never"},
                      {"url": "https://default.example.org/", "mode": "default"},
                      {"url": "https://absent.example.org/"},
                      {"url": "https://unknown.example.org/", "mode": "unknown"},
                      {"url": " ", "mode": "never"}
                    ]
                  }
                }]}]}]}
            """.trimIndent(),
        )
        val request = parse(ImportFormatId.OnePassword, archive).items.single().request
        assertEquals(
            listOf(
                DSecret.Uri("https://host.example.org/", match = DSecret.Uri.MatchType.Host),
                DSecret.Uri("https://never.example.org/", match = DSecret.Uri.MatchType.Never),
                DSecret.Uri("https://default.example.org/"),
                DSecret.Uri("https://absent.example.org/"),
                DSecret.Uri("https://unknown.example.org/"),
                DSecret.Uri("https://primary.example.org/"),
            ),
            request.uris,
        )
    }

    @Test
    fun `1password duplicate primary URL keeps the explicit match mode`() = runTest {
        for ((mode, match) in mapOf("host" to DSecret.Uri.MatchType.Host, "never" to DSecret.Uri.MatchType.Never)) {
            val archive = zip(
                "export.data" to """
                    {"accounts": [{"vaults": [{"items": [{
                      "categoryUuid": "001", "overview": {
                        "title": "Duplicate URL", "url": " https://example.org/ ",
                        "urls": [{"url": "https://example.org/", "mode": "$mode"}]
                      }
                    }]}]}]}
                """.trimIndent(),
            )
            val request = parse(ImportFormatId.OnePassword, archive).items.single().request
            assertEquals(listOf(DSecret.Uri("https://example.org/", match = match)), request.uris, mode)
        }
    }

    @Test
    fun `1password imports a primary URL without URL entries`() = runTest {
        val archive = zip(
            "export.data" to """
                {"accounts": [{"vaults": [{"items": [{
                  "categoryUuid": "001", "overview": {"url": "https://example.org/"}
                }]}]}]}
            """.trimIndent(),
        )
        val request = parse(ImportFormatId.OnePassword, archive).items.single().request
        assertEquals(listOf(DSecret.Uri("https://example.org/")), request.uris)
    }

    private suspend fun parse(
        format: ImportFormatId,
        archive: ByteArray,
    ): ImportPlan {
        val result = importTestService().parse(
            format = format,
            input = BytesImportInput(archive),
            now = IMPORT_TEST_NOW,
        )
        return assertIs<ImportResult.Success>(result).plan
    }

    private fun zip(
        vararg entries: Pair<String, String>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
