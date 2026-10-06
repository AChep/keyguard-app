package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.test.IdentityBase64Service
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SendRequestFileTest {
    @Test
    fun `new file send request includes file metadata and encrypted length`() {
        val model = createFileSend(
            service = BitwardenService(),
        )

        val request = with(TestCryptoGenerator()) {
            with(IdentityBase64Service) {
                SendRequest.of(
                    model = model,
                    key = byteArrayOf(1, 2, 3),
                )
            }
        }

        assertEquals("invoice.pdf", request.file?.fileName)
        assertEquals(321L, request.fileLength)
    }

    @Test
    fun `existing remote file send request omits file metadata and encrypted length`() {
        val model = createFileSend(
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = "remote-send-1",
                    revisionDate = TEST_INSTANT,
                    deletedDate = null,
                ),
            ),
        )

        val request = with(TestCryptoGenerator()) {
            with(IdentityBase64Service) {
                SendRequest.of(
                    model = model,
                    key = byteArrayOf(1, 2, 3),
                )
            }
        }

        assertNull(request.file)
        assertNull(request.fileLength)
    }
}

private fun createFileSend(
    service: BitwardenService,
) = BitwardenSend(
    accountId = "account-1",
    sendId = "send-1",
    accessId = "access-1",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = TEST_INSTANT,
    expirationDate = TEST_INSTANT,
    service = service,
    authType = BitwardenSend.AuthType.None,
    keyBase64 = "send-key",
    name = "Quarterly report",
    notes = "Encrypted file send",
    accessCount = 0,
    type = BitwardenSend.Type.File,
    file = BitwardenSend.File(
        id = "file-1",
        fileName = "invoice.pdf",
        size = 123L,
        pendingUpload = PendingUploadFile(
            path = "/tmp/send-1.bin",
            plainSize = 123L,
            encryptedSize = 321L,
        ),
    ),
)

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
