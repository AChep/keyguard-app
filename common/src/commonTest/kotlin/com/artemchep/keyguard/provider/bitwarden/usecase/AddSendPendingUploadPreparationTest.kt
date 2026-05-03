package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class AddSendPendingUploadPreparationTest {
    @Test
    fun `new file send stages upload once and uses encrypted size`() = runTest {
        val pendingUpload = PendingUploadFile(
            path = "/tmp/send-1.bin",
            plainSize = 123L,
            encryptedSize = 456L,
        )
        val coordinator = SendTestPendingUploadCoordinator(
            stagedUploads = listOf(pendingUpload),
        )
        val prepared = prepareSendPendingUpload(
            request = createSendRequest(
                uri = "file:///tmp/send.pdf",
                name = "send.pdf",
            ),
            old = null,
            send = send(
                file = BitwardenSend.File(
                    id = "file-1",
                    fileName = "send.pdf",
                    keyBase64 = "send-file-key",
                    size = null,
                    pendingUpload = null,
                ),
            ),
            base64Service = SendTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(
            listOf(
                SendTestPendingUploadCoordinator.StageCall(
                    target = PendingUploadTarget.SendFile(
                        accountId = "account-1",
                        sendId = "send-1",
                    ),
                    sourceUri = "file:///tmp/send.pdf",
                    fileKey = "send-file-key",
                ),
            ),
            coordinator.stageCalls,
        )
        assertEquals(listOf(pendingUpload), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(pendingUpload, prepared.send.file?.pendingUpload)
        assertEquals(pendingUpload.encryptedSize, prepared.send.file?.size)
    }

    @Test
    fun `existing staged file send is reused without restaging`() = runTest {
        val pendingUpload = PendingUploadFile(
            path = "/tmp/send-1.bin",
            plainSize = 123L,
            encryptedSize = 456L,
        )
        val existingSend = send(
            file = BitwardenSend.File(
                id = "file-1",
                fileName = "send.pdf",
                keyBase64 = "send-file-key",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val coordinator = SendTestPendingUploadCoordinator()
        val prepared = prepareSendPendingUpload(
            request = createSendRequest(
                uri = "file:///tmp/replacement.pdf",
                name = "replacement.pdf",
            ),
            old = existingSend,
            send = existingSend,
            base64Service = SendTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(existingSend, prepared.send)
    }
}

private fun createSendRequest(
    uri: String,
    name: String,
) = CreateSendRequest(
    ownership = CreateSendRequest.Ownership(
        accountId = "account-1",
    ),
    file = CreateSendRequest.File(
        uri = uri,
        name = name,
    ),
    now = TEST_INSTANT,
)

private fun send(
    file: BitwardenSend.File?,
) = BitwardenSend(
    accountId = "account-1",
    sendId = "send-1",
    accessId = "access-1",
    revisionDate = TEST_INSTANT,
    service = BitwardenService(),
    authType = BitwardenSend.AuthType.None,
    keyBase64 = "send-key",
    name = "Send",
    notes = null,
    accessCount = 0,
    type = BitwardenSend.Type.File,
    file = file,
)

private class SendTestPendingUploadCoordinator(
    stagedUploads: List<PendingUploadFile> = emptyList(),
) : PendingUploadCoordinator {
    private val stagedUploads = ArrayDeque(stagedUploads)

    data class StageCall(
        val target: PendingUploadTarget,
        val sourceUri: String,
        val fileKey: String,
    )

    val stageCalls = mutableListOf<StageCall>()

    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile {
        stageCalls += StageCall(
            target = target,
            sourceUri = sourceUri,
            fileKey = fileKey.decodeToString(),
        )
        return stagedUploads.removeFirstOrNull()
            ?: error("No staged upload prepared for test")
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) = Unit

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ) = Unit

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = false

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = block()
}

private object SendTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
