package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class BitwardenSendFileExtTest {
    @Test
    fun `reconcile preserves pending upload when remote file matches without local upload completion`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = null,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = false,
        )

        assertEquals(pendingUpload, reconciliation.send.file?.pendingUpload)
        assertNull(reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile clears pending upload when remote file matches with local upload completion`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = null,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertNull(reconciliation.send.file?.pendingUpload)
        assertEquals(pendingUpload, reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile clears pending upload when remote file matches local upload completion`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = null,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertNull(reconciliation.send.file?.pendingUpload)
        assertEquals(pendingUpload, reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile preserves pending upload when remote encrypted size differs`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize + 1L,
                pendingUpload = null,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertEquals(pendingUpload, reconciliation.send.file?.pendingUpload)
        assertNull(reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile preserves pending upload when synced remote file id differs`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-2",
                size = pendingUpload.encryptedSize,
                pendingUpload = null,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertEquals(pendingUpload, reconciliation.send.file?.pendingUpload)
        assertNull(reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile preserves pending upload when remote file is missing`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = null,
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertEquals(pendingUpload, reconciliation.send.file?.pendingUpload)
        assertNull(reconciliation.obsoletePendingUpload)
    }

    @Test
    fun `reconcile preserves pending upload when remote send is not a file send`() {
        val pendingUpload = pendingUploadFile()
        val local = fileSend(
            service = remoteService(),
            file = sendFile(
                id = "file-1",
                size = pendingUpload.encryptedSize,
                pendingUpload = pendingUpload,
            ),
        )
        val remote = fileSend(
            service = remoteService(),
            file = null,
        ).copy(
            type = BitwardenSend.Type.Text,
            text = BitwardenSend.Text(
                text = "remote text",
                hidden = false,
            ),
        )

        val reconciliation = remote.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )

        assertEquals(BitwardenSend.Type.File, reconciliation.send.type)
        assertEquals(pendingUpload, reconciliation.send.file?.pendingUpload)
        assertNull(reconciliation.obsoletePendingUpload)
    }
}

private fun fileSend(
    service: BitwardenService,
    file: BitwardenSend.File?,
) = BitwardenSend(
    accountId = "account-1",
    sendId = "send-1",
    accessId = "access-1",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    service = service,
    authType = BitwardenSend.AuthType.None,
    keyBase64 = "send-key",
    name = "Quarterly report",
    notes = "",
    accessCount = 0,
    type = BitwardenSend.Type.File,
    file = file,
)

private fun sendFile(
    id: String,
    size: Long?,
    pendingUpload: PendingUploadFile?,
) = BitwardenSend.File(
    id = id,
    fileName = "invoice.pdf",
    size = size,
    pendingUpload = pendingUpload,
)

private fun remoteService() = BitwardenService(
    remote = BitwardenService.Remote(
        id = "remote-send-1",
        revisionDate = TEST_INSTANT,
        deletedDate = null,
    ),
)

private fun pendingUploadFile() = PendingUploadFile(
    path = "/tmp/send-1.bin",
    plainSize = 123L,
    encryptedSize = 321L,
)

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
