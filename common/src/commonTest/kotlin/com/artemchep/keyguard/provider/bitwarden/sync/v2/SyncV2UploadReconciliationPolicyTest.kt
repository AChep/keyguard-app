package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.mergePendingAttachmentRemoteIdsFrom
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingSendFileUpload
import com.artemchep.keyguard.core.store.bitwarden.withPendingAttachmentRemoteId
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncV2UploadReconciliationPolicyTest {
    @Test
    fun `cipher attachment fallback requires matching key encrypted size and file name`() {
        val pendingUpload = pendingUploadFile(encryptedSize = 42L)
        val localCipher = cipher(
            attachments =
                listOf(
                    localAttachment(
                        id = "local-upload",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        pendingUpload = pendingUpload,
                    ),
                ),
        )

        val matched = localCipher.reconcilePendingLocalAttachments(
            remoteAttachments =
                listOf(
                    remoteAttachment(
                        id = "remote-upload",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        size = 42L,
                    ),
                ),
        )
        val keyMismatch = localCipher.reconcilePendingLocalAttachments(
            remoteAttachments =
                listOf(
                    remoteAttachment(
                        id = "remote-upload",
                        fileName = "report.pdf",
                        keyBase64 = "other-key",
                        size = 42L,
                    ),
                ),
        )
        val sizeMismatch = localCipher.reconcilePendingLocalAttachments(
            remoteAttachments =
                listOf(
                    remoteAttachment(
                        id = "remote-upload",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        size = 43L,
                    ),
                ),
        )
        val nameMismatch = localCipher.reconcilePendingLocalAttachments(
            remoteAttachments =
                listOf(
                    remoteAttachment(
                        id = "remote-upload",
                        fileName = "other.pdf",
                        keyBase64 = "shared-key",
                        size = 42L,
                    ),
                ),
        )

        assertEquals("remote-upload", matched.replacementsByLocalId["local-upload"]?.id)
        assertEquals(listOf(pendingUpload), matched.obsoletePendingUploads)
        assertEquals(emptyMap(), keyMismatch.replacementsByLocalId)
        assertEquals(emptyMap(), sizeMismatch.replacementsByLocalId)
        assertEquals(emptyMap(), nameMismatch.replacementsByLocalId)
    }

    @Test
    fun `cipher attachment explicit uploaded id mismatch does not fall back to metadata match`() {
        val localCipher = cipher(
            attachments =
                listOf(
                    localAttachment(
                        id = "local-upload",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        pendingUpload = pendingUploadFile(encryptedSize = 42L),
                    ),
                ),
        )

        val reconciliation = localCipher.reconcilePendingLocalAttachments(
            remoteAttachments =
                listOf(
                    remoteAttachment(
                        id = "fallback-match",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        size = 42L,
                    ),
                ),
            uploadedRemoteAttachmentIdsByLocalId =
                mapOf(
                    "local-upload" to "different-uploaded-id",
                ),
        )

        assertEquals(emptyMap(), reconciliation.replacementsByLocalId)
        assertEquals(listOf("local-upload"), reconciliation.cipher.attachments.map { it.id })
    }

    @Test
    fun `cipher pending attachment reservation id is preserved for retry metadata`() {
        val pendingUpload = pendingUploadFile(encryptedSize = 42L)
        val localCipher = cipher(
            attachments =
                listOf(
                    localAttachment(
                        id = "local-upload",
                        fileName = "report.pdf",
                        keyBase64 = "shared-key",
                        pendingUpload = pendingUpload,
                    ),
                ),
        )

        val reservedCipher =
            localCipher.withPendingAttachmentRemoteId(
                localAttachmentId = "local-upload",
                remoteAttachmentId = "reserved-remote-id",
            )
        val merged = localCipher.mergePendingAttachmentRemoteIdsFrom(reservedCipher)
        val mergedAttachment =
            merged.attachments
                .filterIsInstance<BitwardenCipher.Attachment.Local>()
                .single()

        assertEquals("reserved-remote-id", mergedAttachment.pendingUpload?.remoteId)
    }

    @Test
    fun `send file fallback requires encrypted size file name and synced file id`() {
        val pendingUpload = pendingUploadFile(encryptedSize = 42L)
        val local = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file =
                sendFile(
                    id = "file-1",
                    fileName = "report.pdf",
                    size = 42L,
                    pendingUpload = pendingUpload,
                ),
        )

        val matched = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file =
                sendFile(
                    id = "file-1",
                    fileName = "report.pdf",
                    size = 42L,
                    pendingUpload = null,
                ),
        ).reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )
        val sizeMismatch = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file = sendFile("file-1", "report.pdf", 43L, null),
        ).reconcilePendingSendFileUpload(local = local, uploadCompletedLocally = true)
        val nameMismatch = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file = sendFile("file-1", "other.pdf", 42L, null),
        ).reconcilePendingSendFileUpload(local = local, uploadCompletedLocally = true)
        val idMismatch = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file = sendFile("file-2", "report.pdf", 42L, null),
        ).reconcilePendingSendFileUpload(local = local, uploadCompletedLocally = true)

        assertNull(matched.send.file?.pendingUpload)
        assertEquals(pendingUpload, matched.obsoletePendingUpload)
        assertEquals(pendingUpload, sizeMismatch.send.file?.pendingUpload)
        assertEquals(pendingUpload, nameMismatch.send.file?.pendingUpload)
        assertEquals(pendingUpload, idMismatch.send.file?.pendingUpload)
    }

    @Test
    fun `unsynced new send file can reconcile by metadata before remote file id exists`() {
        val pendingUpload = pendingUploadFile(encryptedSize = 42L)
        val local = fileSend(
            service = BitwardenService(),
            file =
                sendFile(
                    id = "local-file-id",
                    fileName = "report.pdf",
                    size = 42L,
                    pendingUpload = pendingUpload,
                ),
        )
        val remote = fileSend(
            service = remoteService(remoteId = "remote-send-1"),
            file =
                sendFile(
                    id = "server-file-id",
                    fileName = "report.pdf",
                    size = 42L,
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

    private fun cipher(
        attachments: List<BitwardenCipher.Attachment>,
    ): BitwardenCipher =
        BitwardenCipher(
            accountId = "account-1",
            cipherId = "cipher-1",
            revisionDate = T0,
            createdDate = T0,
            service =
                BitwardenService(
                    remote =
                        BitwardenService.Remote(
                            id = "remote-cipher-1",
                            revisionDate = T0,
                            deletedDate = null,
                        ),
                ),
            keyBase64 = "cipher-key",
            name = "Quarterly report",
            notes = "",
            favorite = false,
            attachments = attachments,
            reprompt = BitwardenCipher.RepromptType.None,
            type = BitwardenCipher.Type.SecureNote,
            secureNote = BitwardenCipher.SecureNote(),
        )

    private fun localAttachment(
        id: String,
        fileName: String,
        keyBase64: String,
        pendingUpload: PendingUploadFile,
    ): BitwardenCipher.Attachment.Local =
        BitwardenCipher.Attachment.Local(
            id = id,
            url = "file:///tmp/$fileName",
            fileName = fileName,
            size = pendingUpload.plainSize,
            keyBase64 = keyBase64,
            pendingUpload = pendingUpload,
        )

    private fun remoteAttachment(
        id: String,
        fileName: String,
        keyBase64: String,
        size: Long,
    ): BitwardenCipher.Attachment.Remote =
        BitwardenCipher.Attachment.Remote(
            id = id,
            url = null,
            fileName = fileName,
            keyBase64 = keyBase64,
            size = size,
        )

    private fun fileSend(
        service: BitwardenService,
        file: BitwardenSend.File?,
    ): BitwardenSend =
        BitwardenSend(
            accountId = "account-1",
            sendId = "send-1",
            accessId = "access-1",
            revisionDate = T0,
            createdDate = T0,
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
        fileName: String,
        size: Long?,
        pendingUpload: PendingUploadFile?,
    ): BitwardenSend.File =
        BitwardenSend.File(
            id = id,
            fileName = fileName,
            size = size,
            pendingUpload = pendingUpload,
        )

    private fun remoteService(remoteId: String): BitwardenService =
        BitwardenService(
            remote =
                BitwardenService.Remote(
                    id = remoteId,
                    revisionDate = T0,
                    deletedDate = null,
                ),
        )

    private fun pendingUploadFile(encryptedSize: Long): PendingUploadFile =
        PendingUploadFile(
            path = "/tmp/upload.bin",
            plainSize = 10L,
            encryptedSize = encryptedSize,
        )
}
