package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.createZipService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.io.readString

class KeyguardZipImporterTest {
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private val service = importTestService()

    @Test
    fun `encrypted archives restore native Keyguard metadata`() = runTest {
        val archive = zip(PASSWORD, mapOf("vault.json" to metadataExport()))
        val plan = assertIs<ImportResult.Success>(parse(archive, PASSWORD)).plan
        assertMetadataImported(plan)
    }

    @Test
    fun `an export imports with its attachments`() = runTest {
        val archive = zip(
            password = PASSWORD,
            entries = mapOf(
                "vault.json" to VAULT,
                "attachments/a1/note.txt" to "hello",
            ),
        )

        assertEquals(
            ImportResult.Failure(ImportError.PasswordRequired),
            parse(archive, password = null),
        )
        assertEquals(
            ImportResult.Failure(ImportError.WrongPassword),
            parse(archive, password = "wrong"),
        )

        val plan = assertIs<ImportResult.Success>(parse(archive, PASSWORD)).plan
        // Read through the Bitwarden JSON reader, but reported as Keyguard.
        assertEquals("Keyguard", plan.source.displayName)
        val item = plan.items.single()
        assertEquals("Doc", item.request.title)
        assertEquals(
            listOf(
                ImportPlan.Attachment(
                    ref = "attachments/a1/note.txt",
                    name = "note.txt",
                    size = 5,
                ),
            ),
            item.attachments,
        )
        // Listed by the vault but missing from the archive.
        assertEquals(1, plan.skips[ImportSkipReason.Attachment])

        val files = service.extractAttachments(
            format = ImportFormatId.KeyguardZip,
            input = BytesImportInput(archive),
            password = Password(PASSWORD),
            refs = item.attachments.map { it.ref }.toSet(),
        ) { _, source -> source.readString() }
        assertEquals(mapOf("attachments/a1/note.txt" to "hello"), files)
    }

    @Test
    fun `an attachment gone from the archive since the review fails the extract`() = runTest {
        // The file the review planned from no longer holds the attachment.
        val archive = zip(
            password = PASSWORD,
            entries = mapOf("vault.json" to VAULT),
        )

        assertFailsWith<IllegalStateException> {
            service.extractAttachments(
                format = ImportFormatId.KeyguardZip,
                input = BytesImportInput(archive),
                password = Password(PASSWORD),
                refs = setOf("attachments/a1/note.txt"),
            ) { _, source -> source.readString() }
        }
    }

    @Test
    fun `an archive without a vault fails the import`() = runTest {
        val archive = zip(
            password = PASSWORD,
            entries = mapOf("other.txt" to "x"),
        )
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parse(archive, PASSWORD),
        )
    }

    @Test
    fun `a file that is not an archive fails the import`() = runTest {
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parse("not a zip".encodeToByteArray(), PASSWORD),
        )
    }

    private suspend fun parse(
        archive: ByteArray,
        password: String?,
    ) = service.parse(
        format = ImportFormatId.KeyguardZip,
        input = BytesImportInput(archive),
        password = password?.let(::Password),
        now = now,
    )

    private suspend fun zip(
        password: String,
        entries: Map<String, String>,
    ): ByteArray {
        val buffer = Buffer()
        createZipService().zip(
            outputStream = buffer,
            config = ZipConfig(
                encryption = ZipConfig.Encryption(password),
            ),
            entries = entries.map { (name, text) ->
                ZipEntry(
                    name = name,
                    data = ZipEntry.Data.In {
                        Buffer().apply { writeString(text) } as Source
                    },
                )
            },
        )
        return buffer.readByteArray()
    }
}

private const val PASSWORD = "archive password"

private val VAULT = """
    {
      "encrypted": false,
      "items": [{
        "id": "i1", "type": 2, "name": "Doc", "secureNote": {"type": 0},
        "attachments": [
          {"id": "a1", "fileName": "note.txt", "size": 5},
          {"id": "missing", "fileName": "gone.txt", "size": 1}
        ]
      }]
    }
""".trimIndent()
