package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.crypto.StreamingFileDecryptor
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toLocalPath
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
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
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
            fileService = fileService,
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
        assertContentEquals(
            "plain".encodeToByteArray(),
            service.readPlaintext(
                pendingUpload = pendingUpload,
                fileKey = "key".encodeToByteArray(),
            ),
        )
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
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
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
        val service = pendingUploadService(
            dirProvider = singleDirProvider(pendingRoot),
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

    @Test
    fun `sweep removes only stale unreferenced artifacts in the requested scope`() = runTest {
        val root = createTempDirectory("pending-upload-service")
        val dirProvider = object : PendingUploadDirProvider {
            override suspend fun get(
                accountId: String,
                namespace: String,
            ): LocalPath = root
                .resolve(namespace)
                .resolve(accountId)
                .toLocalPath()
        }
        val service = pendingUploadService(dirProvider = dirProvider)
        val cutoff = Instant.parse("2026-07-26T07:00:00Z")
        val staleAt = Instant.parse("2026-07-25T07:00:00Z")
        val recentAt = Instant.parse("2026-07-27T07:00:00Z")
        val targetDir = root.resolve("send_uploads").resolve("account-1")
        val referenced = targetDir.resolve("referenced.bin")
            .writeArtifact(staleAt)
        val referencedMarker = targetDir.resolve("referenced.bin.uploaded")
            .writeArtifact(staleAt)
        val orphan = targetDir.resolve("orphan.bin")
            .writeArtifact(staleAt)
        val orphanTemp = targetDir.resolve("orphan.bin.tmp")
            .writeArtifact(staleAt)
        val orphanMarker = targetDir.resolve("orphan.bin.uploaded")
            .writeArtifact(staleAt)
        val recentGroupBase = targetDir.resolve("recent-group.bin")
            .writeArtifact(staleAt)
        val recentGroupMarker = targetDir.resolve("recent-group.bin.uploaded")
            .writeArtifact(recentAt)
        val unknownFile = targetDir.resolve("keep-me.txt")
            .writeArtifact(staleAt)
        val otherAccountOrphan = root
            .resolve("send_uploads")
            .resolve("account-2")
            .resolve("orphan.bin")
            .writeArtifact(staleAt)
        val otherNamespaceOrphan = root
            .resolve("cipher_attachment_uploads")
            .resolve("account-1")
            .resolve("orphan.bin")
            .writeArtifact(staleAt)

        repeat(2) {
            service.sweepOrphans(
                accountId = "account-1",
                namespace = PendingUploadTarget.SendFile.NAMESPACE,
                referencedPaths = setOf(referenced.toString()),
                olderThan = cutoff,
            )
        }

        assertTrue(referenced.exists())
        assertTrue(referencedMarker.exists())
        assertFalse(orphan.exists())
        assertFalse(orphanTemp.exists())
        assertFalse(orphanMarker.exists())
        assertTrue(recentGroupBase.exists())
        assertTrue(recentGroupMarker.exists())
        assertTrue(unknownFile.exists())
        assertTrue(otherAccountOrphan.exists())
        assertTrue(otherNamespaceOrphan.exists())
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

private class CopyingFileEncryptor : FileEncryptor, StreamingFileDecryptor {
    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = input

    override fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream = input

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

private fun Path.writeArtifact(
    modifiedAt: Instant,
): Path = apply {
    parent.toFile().mkdirs()
    writeBytes("artifact".encodeToByteArray())
    Files.setLastModifiedTime(
        this,
        FileTime.fromMillis(modifiedAt.toEpochMilliseconds()),
    )
}

private fun pendingUploadService(
    dirProvider: PendingUploadDirProvider,
    fileService: FileService = ManagedSourceFileService(),
) = EncryptedFilePendingUploadServiceJvm(
    dirProvider = dirProvider,
    fileService = fileService,
    fileEncryptor = CopyingFileEncryptor(),
)

/** Staging directory provider that ignores the account and namespace. */
private fun singleDirProvider(
    dir: Path,
) = object : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): LocalPath = dir.toLocalPath()
}
