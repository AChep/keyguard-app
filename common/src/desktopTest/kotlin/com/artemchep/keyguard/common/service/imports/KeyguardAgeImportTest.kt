package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.impl.ImportArchivePasswordException
import com.artemchep.keyguard.common.service.imports.impl.readArchive
import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.util.age.AgeZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

class KeyguardAgeImportTest {
    @Test
    fun parsesVaultAndExtractsSelectedAttachments() = runTest {
        val output = Buffer()
        AgeZipWriter(output, AgePassword("secret")).use { writer ->
            writer.entry("vault.json") {
                it.writeString(
                    """{"encrypted":false,"items":[
                        {"id":"item","type":1,"name":"Example","login":{"username":"alice"}}
                    ]}""",
                )
            }
            writer.entry("attachments/attachment/file.txt") { it.writeString("attachment bytes") }
            writer.finish()
        }
        val input = BytesImportInput(output.readByteArray(), "export.zip.age")
        val service = importTestService()
        val session = service.open(ImportFormatId.KeyguardZip, input, Password("secret"))
        val result = assertIs<ImportResult.Success>(session.parse(null, IMPORT_TEST_NOW))
        assertEquals("Example", result.plan.items.single().request.title)
        val files = session.extract(setOf("attachments/attachment/file.txt"), object : ImportAttachmentSink<String> {
            override suspend fun write(ref: String, source: kotlinx.io.Source): String = source.readString()
        })
        assertEquals(mapOf("attachments/attachment/file.txt" to "attachment bytes"), files)
        assertFailsWith<ImportArchivePasswordException> {
            input.readArchive(Password("wrong"), allowAge = true) { true }
        }
    }
}
