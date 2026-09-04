package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GpgKeyPrivateExport
import com.artemchep.keyguard.common.usecase.GpgKeyPublicExport
import com.artemchep.keyguard.util.zip.createZipService
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

class KeyExportImplTest {
    @Test
    fun `private export writes ssh text into downloads sink`() {
        val dirsService = RecordingDirsService()
        val useCase = KeyPrivateExportImpl(
            dirsService = dirsService,
            dateFormatter = FixedDateFormatter,
        )

        val uri = useCase(keyParameter(type = KeyPair.Type.ED25519, ssh = "private ssh"))
            .bindBlocking()

        assertEquals("file:///downloads/id_ed25519_20260312T030405", uri)
        val savedFile = dirsService.saved.single()
        assertEquals("id_ed25519_20260312T030405", savedFile.fileName)
        assertContentEquals("private ssh".encodeToByteArray(), savedFile.bytes)
    }

    @Test
    fun `public export writes ssh text into downloads sink`() {
        val dirsService = RecordingDirsService()
        val useCase = KeyPublicExportImpl(
            dirsService = dirsService,
            dateFormatter = FixedDateFormatter,
        )

        val uri = useCase(keyParameter(type = KeyPair.Type.RSA, ssh = "public ssh"))
            .bindBlocking()

        assertEquals("file:///downloads/id_rsa_20260312T030405.pub", uri)
        val savedFile = dirsService.saved.single()
        assertEquals("id_rsa_20260312T030405.pub", savedFile.fileName)
        assertContentEquals("public ssh".encodeToByteArray(), savedFile.bytes)
    }

    @Test
    fun `gpg public export writes armored text into asc file`() {
        val dirsService = RecordingDirsService()
        val useCase = GpgKeyPublicExportImpl(
            dirsService = dirsService,
            dateFormatter = FixedDateFormatter,
        )

        val request = GpgKeyPublicExport.Request(
            fingerprint = "ABCD 1234 EF56 7890 ABCD 1234 EF56 7890 ABCD 1234",
            publicKeyArmored = "public gpg",
        )
        val uri = useCase(request)
            .bindBlocking()

        assertEquals("file:///downloads/gpg_EF567890ABCD1234_20260312T030405.public.asc", uri)
        val savedFile = dirsService.saved.single()
        assertEquals("gpg_EF567890ABCD1234_20260312T030405.public.asc", savedFile.fileName)
        assertContentEquals("public gpg".encodeToByteArray(), savedFile.bytes)
    }

    @Test
    fun `gpg private export without fingerprint falls back to key suffix`() {
        val dirsService = RecordingDirsService()
        val useCase = GpgKeyPrivateExportImpl(
            dirsService = dirsService,
            dateFormatter = FixedDateFormatter,
        )

        val request = GpgKeyPrivateExport.Request(
            fingerprint = "",
            privateKeyArmored = "private gpg",
        )
        val uri = useCase(request)
            .bindBlocking()

        assertEquals("file:///downloads/gpg_key_20260312T030405.private.asc", uri)
        val savedFile = dirsService.saved.single()
        assertContentEquals("private gpg".encodeToByteArray(), savedFile.bytes)
    }

    @Test
    fun `key pair export writes zip entries with same text payloads`() {
        val dirsService = RecordingDirsService()
        val useCase = KeyPairExportImpl(
            dirsService = dirsService,
            zipService = createZipService(),
            dateFormatter = FixedDateFormatter,
        )

        val uri = useCase(
            KeyPair(
                type = KeyPair.Type.ED25519,
                privateKey = keyParameter(
                    type = KeyPair.Type.ED25519,
                    ssh = "private ssh",
                    fingerprint = "private fingerprint",
                ),
                publicKey = keyParameter(
                    type = KeyPair.Type.ED25519,
                    ssh = "public ssh",
                    fingerprint = "public fingerprint",
                ),
            ),
        ).bindBlocking()

        assertEquals("file:///downloads/id_ed25519_20260312T030405.zip", uri)
        assertEquals("id_ed25519_20260312T030405.zip", dirsService.saved.single().fileName)
        assertEquals(
            mapOf(
                "id_ed25519" to "private ssh",
                "id_ed25519.pub" to "public ssh",
            ),
            unzipTexts(dirsService.saved.single().bytes),
        )
    }

    private fun keyParameter(
        type: KeyPair.Type,
        ssh: String,
        fingerprint: String = "fingerprint",
    ) = KeyPair.KeyParameter(
        encoded = ssh.encodeToByteArray(),
        type = type,
        ssh = ssh,
        fingerprint = fingerprint,
    )

    private fun unzipTexts(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                entries[entry.name] = stream.readBytes().decodeToString()
                stream.closeEntry()
            }
        }
        return entries
    }
}

private data class SavedFile(
    val fileName: String,
    val bytes: ByteArray,
)

private class RecordingDirsService : DirsService {
    val saved = mutableListOf<SavedFile>()

    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect {
        val sink = Buffer()
        write(sink)
        saved += SavedFile(
            fileName = fileName,
            bytes = sink.readByteArray(),
        )
        "file:///downloads/$fileName"
    }
}

private object FixedDateFormatter : DateFormatter {
    override fun formatDateTimeMachine(instant: Instant): String = "20260312T030405"

    override fun formatDateTime(instant: Instant): String = error("Unused in test")

    override fun formatDate(instant: Instant): String = error("Unused in test")

    override suspend fun formatDateShort(instant: Instant): String = error("Unused in test")

    override suspend fun formatDateShort(date: LocalDate): String = error("Unused in test")

    override fun formatDateMedium(date: LocalDate): String = error("Unused in test")

    override fun formatTimeShort(time: LocalTime): String = error("Unused in test")
}
