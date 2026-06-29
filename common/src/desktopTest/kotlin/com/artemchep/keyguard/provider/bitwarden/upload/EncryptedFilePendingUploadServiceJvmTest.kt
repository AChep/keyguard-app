package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

class EncryptedFilePendingUploadServiceJvmTest {
    @Test
    fun `stage deletes managed source file after successful staging`() = runTest {
        val root = createTempDirectory("pending-upload-service")
        val source = root.resolve("drop-source.bin").also {
            it.writeBytes("plain".encodeToByteArray())
        }
        val pendingRoot = root.resolve("pending")
        val fileService = ManagedSourceFileService()
        val service = EncryptedFilePendingUploadServiceJvm(
            dirProvider = object : PendingUploadDirProvider {
                override suspend fun get(
                    accountId: String,
                    namespace: String,
                ): LocalPath = pendingRoot.toLocalPath()
            },
            fileService = fileService,
            fileEncryptor = CopyingFileEncryptor(),
        )

        val pendingUpload = service.stage(
            accountId = "account-1",
            namespace = "send_uploads",
            fileId = "send-1",
            sourceUri = source.toUri().toString(),
            fileKey = "key".encodeToByteArray(),
        )

        assertEquals(listOf(source.toUri().toString()), fileService.deletedManagedSources)
        assertFalse(source.exists())
        assertEquals(pendingRoot.resolve("send-1.bin").toString(), pendingUpload.path)
        assertContentEquals("plain".encodeToByteArray(), File(pendingUpload.path).readBytes())
    }

    @Test
    fun `stage clears stale uploaded marker`() = runTest {
        val root = createTempDirectory("pending-upload-service")
        val source = root.resolve("drop-source.bin").also {
            it.writeBytes("plain".encodeToByteArray())
        }
        val pendingRoot = root.resolve("pending")
        val marker = pendingRoot.resolve("send-1.bin.uploaded")
        marker.toFile().parentFile.mkdirs()
        marker.writeBytes("uploaded".encodeToByteArray())
        val service = EncryptedFilePendingUploadServiceJvm(
            dirProvider = object : PendingUploadDirProvider {
                override suspend fun get(
                    accountId: String,
                    namespace: String,
                ): LocalPath = pendingRoot.toLocalPath()
            },
            fileService = ManagedSourceFileService(),
            fileEncryptor = CopyingFileEncryptor(),
        )

        service.stage(
            accountId = "account-1",
            namespace = "send_uploads",
            fileId = "send-1",
            sourceUri = source.toUri().toString(),
            fileKey = "key".encodeToByteArray(),
        )

        assertFalse(marker.exists())
    }

    @Test
    fun `mark uploaded writes marker and delete removes it`() = runTest {
        val root = createTempDirectory("pending-upload-service")
        val pendingRoot = root.resolve("pending")
        val service = EncryptedFilePendingUploadServiceJvm(
            dirProvider = object : PendingUploadDirProvider {
                override suspend fun get(
                    accountId: String,
                    namespace: String,
                ): LocalPath = pendingRoot.toLocalPath()
            },
            fileService = ManagedSourceFileService(),
            fileEncryptor = CopyingFileEncryptor(),
        )
        val stagedFile = pendingRoot.resolve("send-1.bin")
        stagedFile.toFile().parentFile.mkdirs()
        stagedFile.writeBytes("encrypted".encodeToByteArray())
        val pendingUpload = PendingUploadFile(
            path = stagedFile.toString(),
            plainSize = 123L,
            encryptedSize = 321L,
        )

        assertFalse(service.isUploaded(pendingUpload))

        service.markUploaded(pendingUpload)

        assertTrue(service.isUploaded(pendingUpload))
        assertTrue(File("${pendingUpload.path}.uploaded").exists())

        service.delete(pendingUpload)

        assertFalse(service.isUploaded(pendingUpload))
        assertFalse(stagedFile.exists())
    }
}

private class ManagedSourceFileService : FileService {
    val deletedManagedSources = mutableListOf<String>()

    override fun exists(uri: String): Boolean = File(uri.toPath()).exists()

    override fun readFromFile(uri: String): Source = File(uri.toPath())
        .inputStream()
        .asSource()
        .buffered()

    override fun writeToFile(uri: String): Sink = File(uri.toPath())
        .outputStream()
        .asSink()
        .buffered()

    override fun delete(uri: String): Boolean = File(uri.toPath()).delete()

    override fun deleteManagedSourceFile(uri: String): Boolean {
        deletedManagedSources += uri
        return delete(uri)
    }
}

private class CopyingFileEncryptor : FileEncryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = input

    override fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = data

    override fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): FileEncryptor.EncodeResult {
        val data = input.readByteArray()
        File(output.value).writeBytes(data)
        return FileEncryptor.EncodeResult(
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong(),
        )
    }
}

private fun String.toPath(): String =
    java.net.URI(this).let(::File).path
