package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.assertKeyCleared
import com.artemchep.keyguard.provider.bitwarden.upload.StagingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.test.IdentityBase64Service
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val coordinator = StagingPendingUploadCoordinator(
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
                    size = null,
                    pendingUpload = null,
                ),
            ),
            cryptoGenerator = SendTestCryptoGenerator,
            base64Service = IdentityBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(
            listOf(
                StagingPendingUploadCoordinator.StageCall(
                    target = PendingUploadTarget.SendFile(
                        accountId = "account-1",
                        sendId = "send-1",
                    ),
                    sourceUri = "file:///tmp/send.pdf",
                    fileKey = "derived-send-key",
                ),
            ),
            coordinator.stageCalls,
        )
        assertKeyCleared(coordinator.stageFileKeyRefs.single())
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
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val coordinator = StagingPendingUploadCoordinator()
        val prepared = prepareSendPendingUpload(
            request = createSendRequest(
                uri = "file:///tmp/replacement.pdf",
                name = "replacement.pdf",
            ),
            old = existingSend,
            send = existingSend,
            cryptoGenerator = SendTestCryptoGenerator,
            base64Service = IdentityBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), coordinator.stageFileKeyRefs)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(existingSend, prepared.send)
    }

    @Test
    fun `failed file send staging clears derived key`() = runTest {
        val coordinator = StagingPendingUploadCoordinator()

        assertFailsWith<IllegalStateException> {
            prepareSendPendingUpload(
                request = createSendRequest(
                    uri = "file:///tmp/send.pdf",
                    name = "send.pdf",
                ),
                old = null,
                send = send(
                    file = BitwardenSend.File(
                        id = "file-1",
                        fileName = "send.pdf",
                        size = null,
                        pendingUpload = null,
                    ),
                ),
                cryptoGenerator = SendTestCryptoGenerator,
                base64Service = IdentityBase64Service,
                pendingUploadCoordinator = coordinator,
            )
        }

        assertKeyCleared(coordinator.stageFileKeyRefs.single())
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



private object SendTestCryptoGenerator : TestCryptoGenerator() {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = "derived-send-key".encodeToByteArray()
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
