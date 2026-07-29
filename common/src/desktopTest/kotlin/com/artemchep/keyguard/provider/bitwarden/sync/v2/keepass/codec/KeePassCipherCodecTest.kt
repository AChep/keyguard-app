package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlNamespace
import app.keemobile.kotpass.models.XmlQualifiedName
import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestUnusedFileService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase32Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase64Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testJson
import com.artemchep.keyguard.provider.bitwarden.upload.FailingPendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.assertKeyCleared
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

@Suppress("FunctionNaming")
class KeePassCipherCodecTest {
    private val codec = createCodec()

    @Test
    fun `encode preserves remote XML extensions on current and historical entries`() = runTest {
        val extension = XmlExtension(
            name = XmlQualifiedName(
                localName = "PluginData",
                namespaceUri = "urn:keyguard:test-plugin",
                prefix = "plugin",
            ),
            namespaces = listOf(
                XmlNamespace(
                    prefix = "plugin",
                    namespaceUri = "urn:keyguard:test-plugin",
                ),
            ),
            content = listOf(
                XmlExtensionContent.Text(EntryValue.Plain("plugin-value")),
            ),
        )
        val remote = buildEntry(title = "Before edit").copy(
            extensions = listOf(extension),
        )

        val encoded = codec.encode(
            local = testBitwardenCipher(
                cipherId = remote.uuid.toString(),
                name = "After edit",
            ),
            remote = remote,
            existingBinaries = emptyMap(),
        )

        assertEquals(remote.extensions, encoded.entry.extensions)
        assertEquals(remote.extensions, encoded.entry.history.single().extensions)
    }

    @Test
    fun `encode uses the local cipher id for a new entry uuid`() = runTest {
        val cipherId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"

        val encoded = codec.encode(
            local = testBitwardenCipher(cipherId = cipherId),
            remote = null,
            existingBinaries = emptyMap(),
        )

        assertEquals(cipherId, encoded.entry.uuid.toString())
    }

    @Test
    fun `encode reads a staged attachment when the original uri is unavailable`() = runTest {
        val data = "staged attachment".encodeToByteArray()
        val pendingUpload = PendingUploadFile(
            path = "/private/pending/attachment.bin",
            plainSize = data.size.toLong(),
            encryptedSize = data.size.toLong() + 49L,
        )
        val pendingUploadCoordinator = ReadingPendingUploadCoordinator(data)
        val attachmentKey = "attachment-key".encodeToByteArray()
        val local = stagedAttachmentCipher(pendingUpload, attachmentKey)

        val encoded = createCodec(pendingUploadCoordinator).encode(
            local = local,
            remote = null,
            existingBinaries = emptyMap(),
        )

        assertEquals(
            listOf(pendingUpload to attachmentKey.toList()),
            pendingUploadCoordinator.readCalls,
        )
        assertKeyCleared(pendingUploadCoordinator.readFileKeyRefs.single())
        assertEquals(
            listOf("document.txt"),
            encoded.attachments.map { it.fileName },
        )
        assertEquals(data.size.toLong(), encoded.attachments.single().size)
    }

    @Test
    fun `encode clears attachment key when staged read fails`() = runTest {
        assertEncodeClearsAttachmentKey<IllegalStateException>(
            readFailure = IllegalStateException("read failed"),
        )
    }

    @Test
    fun `encode clears attachment key when staged read is cancelled`() = runTest {
        assertEncodeClearsAttachmentKey<CancellationException>(
            readFailure = CancellationException("cancelled"),
        )
    }

    @Test
    fun `encode keeps attachment key cleared when staged size does not match`() = runTest {
        assertEncodeClearsAttachmentKey<IllegalArgumentException>(
            plainSizeDelta = 1L,
        )
    }

    /**
     * Asserts that a failing `encode` of a staged attachment still zeroes the
     * attachment key, whether the read itself fails or its result is rejected.
     */
    private suspend inline fun <reified E : Throwable> assertEncodeClearsAttachmentKey(
        readFailure: Throwable? = null,
        plainSizeDelta: Long = 0L,
    ) {
        val data = "staged attachment".encodeToByteArray()
        val pendingUploadCoordinator = ReadingPendingUploadCoordinator(
            data = data,
            readFailure = readFailure,
        )
        val local = stagedAttachmentCipher(
            pendingUpload = PendingUploadFile(
                path = "/private/pending/attachment.bin",
                plainSize = data.size.toLong() + plainSizeDelta,
                encryptedSize = data.size.toLong() + 49L,
            ),
            attachmentKey = "attachment-key".encodeToByteArray(),
        )

        assertFailsWith<E> {
            createCodec(pendingUploadCoordinator).encode(
                local = local,
                remote = null,
                existingBinaries = emptyMap(),
            )
        }

        assertKeyCleared(pendingUploadCoordinator.readFileKeyRefs.single())
    }

    @Test
    fun `decode collapses duplicate cipher links by canonical target`() = runTest {
        val remote = buildEntry(
            extraFields = linkedMapOf(
                CipherLinkFields.fieldName(2) to EntryValue.Plain(
                    "keyguard://cipher/${TARGET_REMOTE_ID.uppercase()}",
                ),
                CipherLinkFields.fieldName(1) to EntryValue.Plain(
                    "keyguard://cipher/$TARGET_REMOTE_ID",
                ),
            ),
        )

        val decoded = codec.decode(
            accountId = "account",
            folderId = null,
            cipherId = "cipher",
            remote = remote,
            local = testBitwardenCipher(cipherId = "cipher"),
            revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
            binaries = emptyMap(),
        )

        assertEquals(
            listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
            decoded.links,
        )
        assertEquals(emptyList(), decoded.fields)
    }

    private fun createCodec(
        pendingUploadCoordinator: PendingUploadCoordinator = FailingPendingUploadCoordinator,
    ) = KeePassCipherCodec(
        cryptoGenerator = AttachmentCryptoGenerator,
        base32Service = testBase32Service,
        base64Service = testBase64Service,
        fileService = UploadTestUnusedFileService,
        pendingUploadCoordinator = pendingUploadCoordinator,
        getPasswordStrength = UploadTestPasswordStrength,
        json = testJson,
    )
}

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"

private fun stagedAttachmentCipher(
    pendingUpload: PendingUploadFile,
    attachmentKey: ByteArray,
) = testBitwardenCipher(
    cipherId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12",
).copy(
    attachments = listOf(
        BitwardenCipher.Attachment.Local(
            id = "attachment-id",
            url = "content://revoked/original",
            fileName = "document.txt",
            size = pendingUpload.plainSize,
            keyBase64 = testBase64Service.encodeToString(attachmentKey),
            pendingUpload = pendingUpload,
        ),
    ),
)

private object AttachmentCryptoGenerator : CryptoGenerator by testCryptoGenerator {
    override fun hashSha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}

private class ReadingPendingUploadCoordinator(
    private val data: ByteArray,
    private val readFailure: Throwable? = null,
) : PendingUploadCoordinator by FailingPendingUploadCoordinator {
    val readCalls = mutableListOf<Pair<PendingUploadFile, List<Byte>>>()
    val readFileKeyRefs = mutableListOf<ByteArray>()

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray {
        readCalls += pendingUpload to fileKey.toList()
        readFileKeyRefs += fileKey
        readFailure?.let { failure -> throw failure }
        return data
    }
}
