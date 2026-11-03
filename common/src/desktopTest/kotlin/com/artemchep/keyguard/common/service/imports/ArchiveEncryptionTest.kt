package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod

class ArchiveEncryptionTest {
    @Test
    fun `dashlane rejects encrypted archives regardless of the supplied password`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.Dashlane)
    }

    @Test
    fun `proton pass rejects encrypted archives regardless of the supplied password`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.ProtonPass)
    }

    @Test
    fun `1pux rejects encrypted archives regardless of the supplied password`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.OnePassword)
    }

    @Test
    fun `dashlane rejects an encrypted ignored entry after plaintext export data`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.Dashlane, mixed = true)
    }

    @Test
    fun `proton pass rejects an encrypted ignored entry after plaintext export data`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.ProtonPass, mixed = true)
    }

    @Test
    fun `1pux rejects an encrypted ignored entry after plaintext export data`() = runTest {
        assertUnsupportedEncryption(ImportFormatId.OnePassword, mixed = true)
    }

    @Test
    fun `the same export data imports successfully without archive encryption`() = runTest {
        for (format in archiveExports.keys) {
            val result = parse(format, archive(format, EncryptionMethod.NONE), password = null)
            val plan = assertIs<ImportResult.Success>(result, format.name).plan
            assertEquals(listOf("Example"), plan.items.map { it.request.title }, format.name)
        }
    }

    private suspend fun assertUnsupportedEncryption(
        format: ImportFormatId,
        mixed: Boolean = false,
    ) {
        // Collect every result before asserting so a missing-password failure
        // does not hide what happens when the correct password is supplied.
        val results = buildMap {
            for (method in listOf(EncryptionMethod.AES, EncryptionMethod.ZIP_STANDARD)) {
                val bytes = archive(format, method, mixed)
                for ((label, password) in listOf(
                    "missing" to null,
                    "incorrect" to "wrong password",
                    "correct" to ARCHIVE_PASSWORD,
                )) {
                    put("$method / $label", parse(format, bytes, password))
                }
            }
        }
        assertEquals(
            results.keys.associateWith { ImportResult.Failure(ImportError.UnsupportedEncryption) },
            results,
            format.name,
        )
    }

    private suspend fun parse(
        format: ImportFormatId,
        bytes: ByteArray,
        password: String?,
    ): ImportResult = importTestService().parse(
        format = format,
        input = BytesImportInput(bytes),
        password = password?.let(::Password),
        now = IMPORT_TEST_NOW,
    )
}

private const val ARCHIVE_PASSWORD = "archive password"

private val archiveExports = mapOf(
    ImportFormatId.Dashlane to (
        "credentials.csv" to "username,title,password,url\nuser,Example,secret,https://example.com\n"
    ),
    ImportFormatId.ProtonPass to (
        "Proton Pass/data.json" to """
            {"vaults":{"v":{"name":"Personal","items":[
              {"state":1,"data":{"metadata":{"name":"Example"},"type":"note","content":{}}}
            ]}}}
        """.trimIndent()
    ),
    ImportFormatId.OnePassword to (
        "export.data" to """
            {"accounts":[{"vaults":[{"items":[
              {"categoryUuid":"003","overview":{"title":"Example"},"details":{"notesPlain":"Note"}}
            ]}]}]}
        """.trimIndent()
    ),
)

/** A real encrypted ZIP, including entries the importer's content filter ignores. */
private fun archive(
    format: ImportFormatId,
    method: EncryptionMethod,
    mixed: Boolean = false,
): ByteArray {
    val output = ByteArrayOutputStream()
    val entries = listOf(archiveExports.getValue(format), "ignored.txt" to "Ignored content")
    ZipOutputStream(output, ARCHIVE_PASSWORD.toCharArray()).use { stream ->
        entries.forEachIndexed { index, (name, text) ->
            val entryMethod = if (mixed && index == 0) EncryptionMethod.NONE else method
            stream.putNextEntry(ZipParameters().apply {
                fileNameInZip = name
                isEncryptFiles = entryMethod != EncryptionMethod.NONE
                encryptionMethod = entryMethod
            })
            stream.write(text.encodeToByteArray())
            stream.closeEntry()
        }
    }
    return output.toByteArray()
}
