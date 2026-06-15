package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
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

        val coordinator = CipherTestPendingUploadCoordinator()
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
        val coordinator = CipherTestPendingUploadCoordinator(
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
                CipherTestPendingUploadCoordinator.StageCall(
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
    fun `keepass local attachments are not staged for bitwarden upload`() = runTest {
        val removedPendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-2.bin")
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/report.pdf"),
            name = "report.pdf",
            size = 111L,
        )
        val oldCipher = cipher(
            attachments = listOf(
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
        val coordinator = CipherTestPendingUploadCoordinator()
        val prepared = prepareCipherPendingUploads(
            request = createRequest(requestAttachment),
            old = oldCipher,
            cipher = cipher(
                attachments = listOf(
                    BitwardenCipher.Attachment.Local(
                        id = "attachment-1",
                        url = "file:///tmp/report.pdf",
                        fileName = "report.pdf",
                        size = 111L,
                        keyBase64 = "attachment-key",
                        pendingUpload = null,
                    ),
                ),
            ),
            base64Service = CipherTestBase64Service,
            pendingUploadCoordinator = coordinator,
            stagePendingUploads = false,
        )

        assertEquals(emptyList(), coordinator.stageCalls)
        assertEquals(emptyList(), prepared.createdPendingUploads)
        assertEquals(listOf(removedPendingUpload), prepared.removedPendingUploads)
        assertEquals(
            listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment-1",
                    url = "file:///tmp/report.pdf",
                    fileName = "report.pdf",
                    size = 111L,
                    keyBase64 = "attachment-key",
                    pendingUpload = null,
                ),
            ),
            prepared.cipher.attachments,
        )
    }

    @Test
    fun `staged attachments are deleted when later preparation fails`() = runTest {
        val stagedPendingUpload = pendingUploadFile("/tmp/cipher-1.attachment-1.bin")
        val coordinator = CipherTestPendingUploadCoordinator(
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

private class CipherTestPendingUploadCoordinator(
    stagedUploads: List<PendingUploadFile> = emptyList(),
) : PendingUploadCoordinator {
    private val stagedUploads = ArrayDeque(stagedUploads)

    data class StageCall(
        val target: PendingUploadTarget,
        val sourceUri: String,
        val fileKey: String,
    )

    val stageCalls = mutableListOf<StageCall>()
    val deleteCalls = mutableListOf<PendingUploadFile>()

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
    ) {
        deleteCalls += pendingUpload
    }

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

private fun pendingUploadFile(path: String) = PendingUploadFile(
    path = path,
    plainSize = 123L,
    encryptedSize = 321L,
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
