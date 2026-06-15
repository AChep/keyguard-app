package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddCipherAttachmentMappingTest {
    @Test
    fun `editing existing pending local attachment keeps persisted staged upload`() {
        val pendingUpload = PendingUploadFile(
            path = "/tmp/cipher-1.attachment-1.bin",
            plainSize = 123L,
            encryptedSize = 321L,
        )
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-1",
            uri = leParseUri("file:///tmp/invoice-renamed.pdf"),
            name = "invoice-renamed.pdf",
            size = 123L,
        )
        val existingAttachment = BitwardenCipher.Attachment.Local(
            id = "attachment-1",
            url = "file:///tmp/invoice.pdf",
            fileName = "invoice.pdf",
            size = 123L,
            keyBase64 = "attachment-key",
            pendingUpload = pendingUpload,
        )

        val attachment = requestAttachment.toBitwardenLocalAttachment(
            existingAttachment = existingAttachment,
            cryptoGenerator = FakeCryptoGenerator(),
            base64Service = IdentityBase64Service,
        )

        assertEquals("file:///tmp/invoice-renamed.pdf", attachment.url)
        assertEquals("invoice-renamed.pdf", attachment.fileName)
        assertEquals("attachment-key", attachment.keyBase64)
        assertEquals(pendingUpload, attachment.pendingUpload)
    }

    @Test
    fun `new local attachment does not invent pending upload metadata`() {
        val requestAttachment = CreateRequest.Attachment.Local(
            id = "attachment-2",
            uri = leParseUri("file:///tmp/receipt.pdf"),
            name = "receipt.pdf",
            size = 99L,
        )

        val attachment = requestAttachment.toBitwardenLocalAttachment(
            existingAttachment = null,
            cryptoGenerator = FakeCryptoGenerator(),
            base64Service = IdentityBase64Service,
        )

        assertEquals("generated-attachment-key", attachment.keyBase64)
        assertNull(attachment.pendingUpload)
    }
}

private object IdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private class FakeCryptoGenerator : CryptoGenerator {
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
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = byteArrayOf()

    override fun seed(length: Int): ByteArray = "generated-attachment-key".toByteArray()

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = byteArrayOf()

    override fun hashSha1(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashSha256(data: ByteArray): ByteArray = byteArrayOf()

    override fun hashMd5(data: ByteArray): ByteArray = byteArrayOf()

    override fun uuid(): String = "uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}
