package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CipherAttachmentCreateRequestTest {
    @Test
    fun `personal cipher attachment request encrypts metadata and uses upload size`() {
        val request = CipherAttachmentCreateRequest.of(
            cipher = createCipher(),
            attachment = createAttachment(),
            itemCrypto = createItemCrypto(),
        )

        assertEquals("enc:2:attachment-key", request.key)
        assertEquals("enc:2:AGENTS.pdf", request.fileName)
        assertEquals(321L, request.fileSize)
        assertEquals(false, request.adminRequest)
        assertEquals(ATTACHMENT_TEST_INSTANT, request.lastKnownRevisionDate)
    }

    @Test
    fun `organization cipher attachment request uses normal edit path by default`() {
        val request = CipherAttachmentCreateRequest.of(
            cipher = createCipher(
                organizationId = "org-1",
            ),
            attachment = createAttachment(),
            itemCrypto = createItemCrypto(),
        )

        assertEquals("enc:2:attachment-key", request.key)
        assertEquals("enc:2:AGENTS.pdf", request.fileName)
        assertEquals(321L, request.fileSize)
        assertEquals(false, request.adminRequest)
        assertEquals(ATTACHMENT_TEST_INSTANT, request.lastKnownRevisionDate)
    }
}

private fun createCipher(
    organizationId: String? = null,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    organizationId = organizationId,
    revisionDate = ATTACHMENT_TEST_INSTANT,
    createdDate = ATTACHMENT_TEST_INSTANT,
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = "remote-cipher-1",
            revisionDate = ATTACHMENT_TEST_INSTANT,
            deletedDate = null,
        ),
    ),
    keyBase64 = "cipher-key",
    name = "Quarterly report",
    notes = "",
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    secureNote = BitwardenCipher.SecureNote(),
)

private fun createAttachment() = BitwardenCipher.Attachment.Local(
    id = "attachment-1",
    url = "file:///tmp/AGENTS.pdf",
    fileName = "AGENTS.pdf",
    size = 123L,
    keyBase64 = "attachment-key",
    pendingUpload = PendingUploadFile(
        path = "/tmp/cipher-1.attachment-1.bin",
        plainSize = 123L,
        encryptedSize = 321L,
    ),
)

private fun createItemCrypto(): BitwardenCrCta = FakeBitwardenCr.cta(
    env = BitwardenCrCta.BitwardenCrCtaEnv(
        key = BitwardenCrKey.CryptoKey(
            symmetricCryptoKey = SymmetricCryptoKey2(
                data = "cipher-key".toByteArray(),
            ),
        ),
    ),
    mode = BitwardenCrCta.Mode.ENCRYPT,
)

private val ATTACHMENT_TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private object AttachmentRequestIdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private object FakeBitwardenCr : BitwardenCr {
    override val base64Service: Base64Service = AttachmentRequestIdentityBase64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = error("unused")

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = { type, data ->
        "enc:${type.type}:${String(data)}"
    }

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}
