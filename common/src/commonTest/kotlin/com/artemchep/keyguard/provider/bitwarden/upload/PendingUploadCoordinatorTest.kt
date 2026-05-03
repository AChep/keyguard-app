package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.provider.bitwarden.upload.impl.PendingUploadCoordinatorImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PendingUploadCoordinatorTest {
    @Test
    fun `stage maps cipher attachment and send targets to low level encrypted service`() = runTest {
        val encryptedService = FakeEncryptedFilePendingUploadService()
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)

        coordinator.stage(
            target = PendingUploadTarget.CipherAttachment(
                accountId = "account-1",
                cipherId = "cipher-1",
                attachmentId = "attachment-1",
            ),
            sourceUri = "file:///tmp/report.pdf",
            fileKey = "cipher-key".toByteArray(),
        )
        coordinator.stage(
            target = PendingUploadTarget.SendFile(
                accountId = "account-2",
                sendId = "send-1",
            ),
            sourceUri = "file:///tmp/send.pdf",
            fileKey = "send-key".toByteArray(),
        )

        assertEquals(
            listOf(
                StageCall(
                    accountId = "account-1",
                    namespace = "cipher_attachment_uploads",
                    fileId = "cipher-1.attachment-1",
                    sourceUri = "file:///tmp/report.pdf",
                    fileKey = "cipher-key",
                ),
                StageCall(
                    accountId = "account-2",
                    namespace = "send_uploads",
                    fileId = "send-1",
                    sourceUri = "file:///tmp/send.pdf",
                    fileKey = "send-key",
                ),
            ),
            encryptedService.stageCalls,
        )
    }

    @Test
    fun `persist rolls back newly created staged files when persistence fails`() = runTest {
        val encryptedService = FakeEncryptedFilePendingUploadService()
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)
        val createdPendingUpload = pendingUploadFile("/tmp/new-upload.bin")
        val removedPendingUpload = pendingUploadFile("/tmp/old-upload.bin")

        val error = assertFailsWith<IllegalStateException> {
            coordinator.persist(
                createdPendingUploads = listOf(createdPendingUpload),
                removedPendingUploads = listOf(removedPendingUpload),
            ) {
                throw IllegalStateException("boom")
            }
        }

        assertEquals("boom", error.message)
        assertEquals(listOf(createdPendingUpload), encryptedService.deletedUploads)
    }

    @Test
    fun `persist cleans up removed staged files after successful persistence`() = runTest {
        val encryptedService = FakeEncryptedFilePendingUploadService()
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)
        val createdPendingUpload = pendingUploadFile("/tmp/new-upload.bin")
        val removedPendingUpload = pendingUploadFile("/tmp/old-upload.bin")

        val result = coordinator.persist(
            createdPendingUploads = listOf(createdPendingUpload),
            removedPendingUploads = listOf(removedPendingUpload),
        ) {
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(removedPendingUpload), encryptedService.deletedUploads)
    }

    @Test
    fun `persist ignores cleanup delete failures`() = runTest {
        val failingUpload = pendingUploadFile("/tmp/failing.bin")
        val succeedingUpload = pendingUploadFile("/tmp/succeeding.bin")
        val encryptedService = FakeEncryptedFilePendingUploadService(
            deleteFailures = setOf(failingUpload.path),
        )
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)

        val result = coordinator.persist(
            createdPendingUploads = emptyList(),
            removedPendingUploads = listOf(failingUpload, succeedingUpload),
        ) {
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(failingUpload, succeedingUpload), encryptedService.deletedUploads)
    }

    @Test
    fun `mark uploaded makes pending upload report uploaded`() = runTest {
        val encryptedService = FakeEncryptedFilePendingUploadService()
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)
        val pendingUpload = pendingUploadFile("/tmp/send-1.bin")

        assertFalse(coordinator.isUploaded(pendingUpload))

        coordinator.markUploaded(pendingUpload)

        assertTrue(coordinator.isUploaded(pendingUpload))
    }

    @Test
    fun `delete clears uploaded marker`() = runTest {
        val encryptedService = FakeEncryptedFilePendingUploadService()
        val coordinator = PendingUploadCoordinatorImpl(encryptedService)
        val pendingUpload = pendingUploadFile("/tmp/send-1.bin")

        coordinator.markUploaded(pendingUpload)
        coordinator.delete(pendingUpload)

        assertFalse(coordinator.isUploaded(pendingUpload))
    }
}

private class FakeEncryptedFilePendingUploadService(
    private val deleteFailures: Set<String> = emptySet(),
) : EncryptedFilePendingUploadService {
    val stageCalls = mutableListOf<StageCall>()
    val deletedUploads = mutableListOf<PendingUploadFile>()
    private val uploadedPaths = mutableSetOf<String>()

    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile {
        stageCalls += StageCall(
            accountId = accountId,
            namespace = namespace,
            fileId = fileId,
            sourceUri = sourceUri,
            fileKey = fileKey.decodeToString(),
        )
        return pendingUploadFile("/tmp/$fileId.bin")
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) {
        deletedUploads += pendingUpload
        uploadedPaths -= pendingUpload.path
        if (pendingUpload.path in deleteFailures) {
            error("delete failure")
        }
    }

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ) {
        uploadedPaths += pendingUpload.path
    }

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = pendingUpload.path in uploadedPaths
}

private data class StageCall(
    val accountId: String,
    val namespace: String,
    val fileId: String,
    val sourceUri: String,
    val fileKey: String,
)

private fun pendingUploadFile(path: String) = PendingUploadFile(
    path = path,
    plainSize = 123L,
    encryptedSize = 321L,
)
