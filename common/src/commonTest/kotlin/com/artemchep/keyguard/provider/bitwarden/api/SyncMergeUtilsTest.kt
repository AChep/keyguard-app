package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingLocalAttachments
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SyncMergeUtilsTest {
    @Test
    fun `merge keeps local attachment order, rename, deletion and pending uploads`() = runTest {
        val baseRemote = createCipher(
            attachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a.txt"),
                remoteAttachment(id = "remote-b", fileName = "b.txt"),
                remoteAttachment(id = "remote-c", fileName = "c.txt"),
            ),
        )
        val local = baseRemote.copy(
            attachments = listOf(
                remoteAttachment(id = "remote-b", fileName = "b-renamed.txt"),
                BitwardenCipher.Attachment.Local(
                    id = "local-upload",
                    url = "file:///tmp/new.bin",
                    fileName = "new.bin",
                    size = 10L,
                    keyBase64 = "local-key",
                    pendingUpload = PendingUploadFile(
                        path = "/tmp/local-upload.bin",
                        plainSize = 10L,
                        encryptedSize = 42L,
                    ),
                ),
                remoteAttachment(id = "remote-c", fileName = "c.txt"),
            ),
            remoteEntity = baseRemote,
        )

        val merged = merge(
            remote = baseRemote,
            local = local,
            getPasswordStrength = fakeGetPasswordStrength,
        )

        assertEquals(
            listOf("remote-b", "local-upload", "remote-c"),
            merged.attachments.map { it.id },
        )
        val renamed = assertIs<BitwardenCipher.Attachment.Remote>(merged.attachments[0])
        assertEquals("b-renamed.txt", renamed.fileName)
        assertIs<BitwardenCipher.Attachment.Local>(merged.attachments[1])
    }

    @Test
    fun `merge keeps remote attachment rename when local name is unchanged from base`() = runTest {
        val baseRemote = createCipher(
            attachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a.txt"),
            ),
        )
        val local = baseRemote.copy(
            remoteEntity = baseRemote,
        )
        val remote = baseRemote.copy(
            attachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a-renamed.txt"),
            ),
        )

        val merged = merge(
            remote = remote,
            local = local,
            getPasswordStrength = fakeGetPasswordStrength,
        )

        val attachment = assertIs<BitwardenCipher.Attachment.Remote>(merged.attachments.single())
        assertEquals("a-renamed.txt", attachment.fileName)
    }

    @Test
    fun `merge keeps pending local attachment even when remote has same key`() = runTest {
        val pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin")
        val local = createCipher(
            attachments = listOf(
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUpload,
                ),
            ),
        )
        val remote = createCipher(
            attachments = listOf(
                remoteAttachment(
                    id = "remote-uploaded",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                ),
            ),
        )

        val merged = merge(
            remote = remote,
            local = local,
            getPasswordStrength = fakeGetPasswordStrength,
        )

        assertEquals(
            listOf("local-upload", "remote-uploaded"),
            merged.attachments.map { it.id },
        )
        assertEquals(pendingUpload, (merged.attachments[0] as BitwardenCipher.Attachment.Local).pendingUpload)
    }

    @Test
    fun `reconcile replaces pending local attachment with remote match by key`() {
        val pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin")
        val cipher = createCipher(
            attachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a.txt"),
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUpload,
                ),
                remoteAttachment(id = "remote-b", fileName = "b.txt"),
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a.txt"),
                remoteAttachment(
                    id = "remote-uploaded",
                    fileName = "uploaded.bin",
                    keyBase64 = "shared-key",
                ),
                remoteAttachment(id = "remote-b", fileName = "b.txt"),
            ),
        )

        assertEquals(
            listOf("remote-a", "remote-uploaded", "remote-b"),
            reconciliation.cipher.attachments.map { it.id },
        )
        assertEquals(listOf(pendingUpload), reconciliation.obsoletePendingUploads)
        assertEquals("remote-uploaded", reconciliation.replacementsByLocalId["local-upload"]?.id)
        assertIs<BitwardenCipher.Attachment.Remote>(reconciliation.cipher.attachments[1])
    }

    @Test
    fun `reconcile preserves reserved pending upload until upload completes locally`() {
        val pendingUpload = pendingUploadFile(
            path = "/tmp/local-upload.bin",
            remoteId = "reserved-id",
        )
        val cipher = createCipher(
            attachments = listOf(
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUpload,
                ),
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(
                remoteAttachment(
                    id = "reserved-id",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                ),
            ),
        )

        assertEquals(listOf("local-upload"), reconciliation.cipher.attachments.map { it.id })
        assertEquals(emptyList(), reconciliation.obsoletePendingUploads)
    }

    @Test
    fun `reconcile keeps local ordering while healing only the matched pending attachment`() {
        val pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin")
        val untouchedPendingUpload = pendingUploadFile(path = "/tmp/other-upload.bin")
        val cipher = createCipher(
            attachments = listOf(
                remoteAttachment(id = "remote-b", fileName = "b-renamed.txt"),
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUpload,
                ),
                remoteAttachment(id = "remote-c", fileName = "c.txt"),
                localAttachment(
                    id = "local-still-pending",
                    fileName = "still-pending.bin",
                    keyBase64 = "unmatched-key",
                    pendingUpload = untouchedPendingUpload,
                ),
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(
                remoteAttachment(id = "remote-a", fileName = "a.txt"),
                remoteAttachment(id = "remote-b", fileName = "b.txt"),
                remoteAttachment(
                    id = "remote-uploaded",
                    fileName = "uploaded.bin",
                    keyBase64 = "shared-key",
                ),
                remoteAttachment(id = "remote-c", fileName = "c.txt"),
            ),
        )

        assertEquals(
            listOf("remote-b", "remote-uploaded", "remote-c", "local-still-pending"),
            reconciliation.cipher.attachments.map { it.id },
        )
        assertEquals(listOf(pendingUpload), reconciliation.obsoletePendingUploads)
        assertIs<BitwardenCipher.Attachment.Local>(reconciliation.cipher.attachments.last())
    }

    @Test
    fun `reconcile prefers uploaded attachment id over key fallback`() {
        val cipher = createCipher(
            attachments = listOf(
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin"),
                ),
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(
                remoteAttachment(
                    id = "fallback-by-key",
                    fileName = "fallback.bin",
                    keyBase64 = "shared-key",
                ),
                remoteAttachment(
                    id = "uploaded-id",
                    fileName = "uploaded.bin",
                    keyBase64 = "other-key",
                ),
            ),
            uploadedRemoteAttachmentIdsByLocalId = mapOf(
                "local-upload" to "uploaded-id",
            ),
        )

        assertEquals("uploaded-id", reconciliation.replacementsByLocalId["local-upload"]?.id)
        assertEquals(listOf("uploaded-id"), reconciliation.cipher.attachments.map { it.id })
    }

    @Test
    fun `reconcile removes duplicate remote replacement already present in merged attachments`() {
        val pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin")
        val remoteReplacement = remoteAttachment(
            id = "remote-uploaded",
            fileName = "uploaded.bin",
            keyBase64 = "shared-key",
        )
        val cipher = createCipher(
            attachments = listOf(
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUpload,
                ),
                remoteReplacement,
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(remoteReplacement),
        )

        assertEquals(
            listOf("remote-uploaded"),
            reconciliation.cipher.attachments.map { it.id },
        )
        assertEquals(listOf(pendingUpload), reconciliation.obsoletePendingUploads)
    }

    @Test
    fun `reconcile falls back to key when uploaded attachment id is missing`() {
        val cipher = createCipher(
            attachments = listOf(
                localAttachment(
                    id = "local-upload",
                    fileName = "new.bin",
                    keyBase64 = "shared-key",
                    pendingUpload = pendingUploadFile(path = "/tmp/local-upload.bin"),
                ),
            ),
        )

        val reconciliation = cipher.reconcilePendingLocalAttachments(
            remoteAttachments = listOf(
                remoteAttachment(
                    id = "fallback-by-key",
                    fileName = "fallback.bin",
                    keyBase64 = "shared-key",
                ),
            ),
            uploadedRemoteAttachmentIdsByLocalId = mapOf(
                "local-upload" to "missing-uploaded-id",
            ),
        )

        assertEquals("fallback-by-key", reconciliation.replacementsByLocalId["local-upload"]?.id)
        assertEquals(listOf("fallback-by-key"), reconciliation.cipher.attachments.map { it.id })
    }
}

private fun createCipher(
    attachments: List<BitwardenCipher.Attachment>,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = "remote-cipher-1",
            revisionDate = TEST_INSTANT,
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

private fun remoteAttachment(
    id: String,
    fileName: String,
    keyBase64: String = "$id-key",
) = BitwardenCipher.Attachment.Remote(
    id = id,
    url = null,
    fileName = fileName,
    keyBase64 = keyBase64,
    size = 123L,
)

private fun localAttachment(
    id: String,
    fileName: String,
    keyBase64: String,
    pendingUpload: PendingUploadFile,
) = BitwardenCipher.Attachment.Local(
    id = id,
    url = "file:///tmp/$fileName",
    fileName = fileName,
    size = pendingUpload.plainSize,
    keyBase64 = keyBase64,
    pendingUpload = pendingUpload,
)

private fun pendingUploadFile(
    path: String,
    remoteId: String? = null,
) = PendingUploadFile(
    path = path,
    plainSize = 10L,
    encryptedSize = 42L,
    remoteId = remoteId,
)

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private val fakeGetPasswordStrength = object : GetPasswordStrength {
    override fun invoke(password: String): IO<PasswordStrength> = ioEffect {
        error("unused in attachment merge tests")
    }
}
