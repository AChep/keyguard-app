package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SendMappingTest {
    @Test
    fun `pending upload makes file send unsynced even if revision dates match`() = runTest {
        val send = BitwardenSend(
            accountId = "account-1",
            sendId = "send-1",
            accessId = "access-1",
            revisionDate = TEST_INSTANT,
            createdDate = TEST_INSTANT,
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = "remote-send-1",
                    revisionDate = TEST_INSTANT,
                    deletedDate = null,
                ),
            ),
            authType = BitwardenSend.AuthType.None,
            keyBase64 = "send-key",
            name = "Quarterly report",
            notes = "",
            accessCount = 0,
            type = BitwardenSend.Type.File,
            file = BitwardenSend.File(
                id = "file-1",
                fileName = "invoice.pdf",
                pendingUpload = PendingUploadFile(
                    path = "/tmp/send-1.bin",
                    plainSize = 123L,
                    encryptedSize = 321L,
                ),
            ),
        )

        val domain = send.toDomain()

        assertFalse(domain.synced)
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
