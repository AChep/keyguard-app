package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.upload.assertKeyCleared
import com.artemchep.keyguard.provider.bitwarden.upload.StagingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.pendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class AddCipherPendingUploadPreparationTest {
    @Test
    fun `editing existing staged attachment reuses pending upload and marks removed uploads for cleanup`() = runTest {
        val existingPendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-1.bin")
        val removedPendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-2.bin")
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/renamed.pdf"),
            name = "renamed.pdf",
            size = null,
        )
        val existingAttachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "file:///tmp/original.pdf",
            fileName = "original.pdf",
            size = null,
            keyBase64 = "attachment-key",
            pendingUpload = existingPendingUpload,
        )
        val oldCipher = cipher(
            attachments = listOf(
                existingAttachment,
                BitwardenCipher.Attachment.Local(
                    id = "attachment-2",
                    url = "file:///tmp/removed.pdf",
                    fileName = "removed.pdf",
                    size = null,
                    keyBase64 = "removed-key",
                    pendingUpload = removedPendingUpload,
                ),
            ),
        )
        val newAttachment = requestAttachment.toBitwardenLocalAttachment(
            existingAttachment = existingAttachment,
            cryptoGenerator = CipherTestCryptoGenerator(),
            base64Service = CipherTestBase64Service,
        )

        val coordinator = StagingPendingUploadCoordinator()
        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = oldCipher,
            cipher = cipher(attachments = listOf(newAttachment)),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(listOf(removedPendingUpload), prepared.removedPendingUploads)
        assertEquals(
            listOf(
                newAttachment.copy(
                    size = existingPendingUpload.plainSize,
                    pendingUpload = existingPendingUpload,
                ),
            ),
            prepared.cipher.attachments,
        )
    }

    @Test
    fun `new staged attachment uses plain size from coordinator result`() = runTest {
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/report.pdf"),
            name = "report.pdf",
            size = null,
        )
        val pendingUpload = PendingUploadFile(
            path = "/tmp/cipher-1.attachment-1.bin",
            plainSize = 111L,
            encryptedSize = 222L,
        )
        val coordinator = StagingPendingUploadCoordinator(
            stagedUploads = listOf(pendingUpload),
        )
        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = null,
            cipher = cipher(
                attachments = listOf(
                    BitwardenCipher.Attachment.Local(
                        id = "attachment-1",
                        url = "file:///tmp/report.pdf",
                        fileName = "report.pdf",
                        size = null,
                        keyBase64 = "attachment-key",
                        pendingUpload = null,
                    ),
                ),
            ),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(
            listOf(
                StagingPendingUploadCoordinator.StageCall(
                    target = PendingUploadTarget.CipherAttachment(
                        accountId = "account-1",
                        cipherId = "cipher-1",
                        attachmentId = "attachment-1",
                    ),
                    sourceUri = "file:///tmp/report.pdf",
                    fileKey = "attachment-key",
                ),
            ),
            coordinator.stageCalls,
        )
        assertKeyCleared(coordinator.stageFileKeyRefs.single())
        assertEquals(listOf(pendingUpload), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(
            listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment-1",
                    url = "file:///tmp/report.pdf",
                    fileName = "report.pdf",
                    size = pendingUpload.plainSize,
                    keyBase64 = "attachment-key",
                    pendingUpload = pendingUpload,
                ),
            ),
            prepared.cipher.attachments,
        )
    }

    @Test
    fun `existing URI-only attachment is not backfilled when staging could succeed`() = runTest {
        val attachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "content://revoked/original",
            fileName = "report.pdf",
            size = 111L,
            keyBase64 = "attachment-key",
            pendingUpload = null,
        )
        val requestAttachment = CreateRequest.Attachment.Local(
            id = attachment.id,
            uri = leParseUri(attachment.url),
            name = attachment.fileName,
            size = attachment.size,
        )
        val coordinator = StagingPendingUploadCoordinator(
            stagedUploads = listOf(
                pendingUploadFile("/tmp/cipher-1.attachment-1.bin"),
            ),
        )

        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = cipher(attachments = listOf(attachment)),
            cipher = cipher(attachments = listOf(attachment)),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), coordinator.stageFileKeyRefs)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(listOf(attachment), prepared.cipher.attachments)
    }

    @Test
    fun `renaming existing URI-only attachment does not stage it`() = runTest {
        val oldAttachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "content://legacy/original",
            fileName = "old-name.pdf",
            size = 111L,
            keyBase64 = "attachment-key",
            pendingUpload = null,
        )
        val renamedAttachment = oldAttachment.copy(
            fileName = "new-name.pdf",
        )
        val requestAttachment = CreateRequest.Attachment.Local(
            id = renamedAttachment.id,
            uri = leParseUri(renamedAttachment.url),
            name = renamedAttachment.fileName,
            size = renamedAttachment.size,
        )
        val coordinator = StagingPendingUploadCoordinator(
            stagedUploads = listOf(
                pendingUploadFile("/tmp/cipher-1.attachment-1.bin"),
            ),
        )

        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = cipher(attachments = listOf(oldAttachment)),
            cipher = cipher(attachments = listOf(renamedAttachment)),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), coordinator.stageFileKeyRefs)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(listOf(renamedAttachment), prepared.cipher.attachments)
    }

    @Test
    fun `replacing existing URI-only attachment stages the new source`() = runTest {
        val oldAttachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "content://legacy/original",
            fileName = "report.pdf",
            size = 111L,
            keyBase64 = "attachment-key",
            pendingUpload = null,
        )
        val replacement = oldAttachment.copy(
            url = "content://documents/replacement",
            size = null,
        )
        val requestAttachment = CreateRequest.Attachment.Local(
            id = replacement.id,
            uri = leParseUri(replacement.url),
            name = replacement.fileName,
            size = replacement.size,
        )
        val pendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-1.bin")
        val coordinator = StagingPendingUploadCoordinator(
            stagedUploads = listOf(pendingUpload),
        )

        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = cipher(attachments = listOf(oldAttachment)),
            cipher = cipher(attachments = listOf(replacement)),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
        )

        assertEquals(
            listOf(
                StagingPendingUploadCoordinator.StageCall(
                    target = PendingUploadTarget.CipherAttachment(
                        accountId = "account-1",
                        cipherId = "cipher-1",
                        attachmentId = replacement.id,
                    ),
                    sourceUri = replacement.url,
                    fileKey = "attachment-key",
                ),
            ),
            coordinator.stageCalls,
        )
        assertEquals(listOf(pendingUpload), prepared.createdPendingUploads)
        assertEquals(emptyList(), prepared.removedPendingUploads)
        assertEquals(
            listOf(
                replacement.copy(
                    size = pendingUpload.plainSize,
                    pendingUpload = pendingUpload,
                ),
            ),
            prepared.cipher.attachments,
        )
    }

    @Test
    fun `failed replacement does not fall back to a raw URI`() = runTest {
        val oldAttachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "content://legacy/original",
            fileName = "report.pdf",
            size = 111L,
            keyBase64 = "attachment-key",
            pendingUpload = null,
        )
        val replacement = oldAttachment.copy(
            url = "content://revoked/replacement",
        )
        val requestAttachment = CreateRequest.Attachment.Local(
            id = replacement.id,
            uri = leParseUri(replacement.url),
            name = replacement.fileName,
            size = replacement.size,
        )
        val coordinator = StagingPendingUploadCoordinator()

        assertFailsWith<IllegalStateException> {
            prepareCipherPendingUploads(
                request = createRequest(requestAttachment),
                old = cipher(attachments = listOf(oldAttachment)),
                cipher = cipher(attachments = listOf(replacement)),
                base64Service = CipherTestBase64Service,
                pendingUploadCoordinator = coordinator,
            )
        }

        assertEquals(
            listOf(
                StagingPendingUploadCoordinator.StageCall(
                    target = PendingUploadTarget.CipherAttachment(
                        accountId = "account-1",
                        cipherId = "cipher-1",
                        attachmentId = replacement.id,
                    ),
                    sourceUri = replacement.url,
                    fileKey = "attachment-key",
                ),
            ),
            coordinator.stageCalls,
        )
        assertKeyCleared(coordinator.stageFileKeyRefs.single())
        assertEquals(emptyList(), coordinator.deleteCalls)
    }

    @Test
    fun `staging cancellation clears attachment key`() = runTest {
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/report.pdf"),
            name = "report.pdf",
            size = null,
        )
        val coordinator = StagingPendingUploadCoordinator(
            stageFailure = CancellationException("cancelled"),
        )

        assertFailsWith<CancellationException> {
            prepareCipherPendingUploads(
                request = createRequest(requestAttachment),
                old = null,
                cipher = cipher(
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-1",
                            url = "file:///tmp/report.pdf",
                            fileName = "report.pdf",
                            size = null,
                            keyBase64 = "attachment-key",
                            pendingUpload = null,
                        ),
                    ),
                ),
                base64Service = CipherTestBase64Service,
                pendingUploadCoordinator = coordinator,
            )
        }

        assertKeyCleared(coordinator.stageFileKeyRefs.single())
        assertEquals(emptyList(), coordinator.deleteCalls)
    }

    @Test
    fun `staged attachments are deleted when later preparation fails`() = runTest {
        val stagedPendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-1.bin")
        val coordinator = StagingPendingUploadCoordinator(
            stagedUploads = listOf(stagedPendingUpload),
        )
        val attachment1 = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/report.pdf"),
            name = "report.pdf",
            size = null,
        )
        val attachment2 = CreateRequest.Attachment.Local(
            id = "attachment-2",
            uri = leParseUri("file:///tmp/missing.pdf"),
            name = "missing.pdf",
            size = null,
        )

        assertFailsWith<IllegalStateException> {
            prepareCipherPendingUploads(
                request = createRequest(attachment1, attachment2),
                old = null,
                cipher = cipher(
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-1",
                            url = "file:///tmp/report.pdf",
                            fileName = "report.pdf",
                            size = null,
                            keyBase64 = "attachment-key",
                            pendingUpload = null,
                        ),
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-2",
                            url = "file:///tmp/missing.pdf",
                            fileName = "missing.pdf",
                            size = null,
                            keyBase64 = "missing-key",
                            pendingUpload = null,
                        ),
                    ),
                ),
                base64Service = CipherTestBase64Service,
                pendingUploadCoordinator = coordinator,
            )
        }

        assertEquals(listOf(stagedPendingUpload), coordinator.deleteCalls)
        assertEquals(2, coordinator.stageFileKeyRefs.size)
        coordinator.stageFileKeyRefs.forEach(::assertKeyCleared)
    }
}

private fun createRequest(
    vararg attachment: CreateRequest.Attachment.Local,
) = CreateRequest(
    attachments = kotlinx.collections.immutable.persistentListOf(*attachment),
    now = TEST_INSTANT,
)

private fun cipher(
    attachments: List<BitwardenCipher.Attachment>,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = TEST_INSTANT,
    service = BitwardenService(),
    name = "Cipher",
    notes = null,
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    attachments = attachments,
)




private object CipherTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private class CipherTestCryptoGenerator : com.artemchep.keyguard.common.service.crypto.CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = byteArrayOf()

    override fun argon2(
        mode: com.artemchep.keyguard.common.model.Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = byteArrayOf()

    override fun seed(length: Int): ByteArray = "generated-key".toByteArray()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: com.artemchep.keyguard.common.model.CryptoHashAlgorithm,
    ): ByteArray = byteArrayOf()

    override fun hashSha1(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashSha256(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashMd5(data: ByteArray): ByteArray = byteArrayOf()

    override fun uuid(): String = "uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
