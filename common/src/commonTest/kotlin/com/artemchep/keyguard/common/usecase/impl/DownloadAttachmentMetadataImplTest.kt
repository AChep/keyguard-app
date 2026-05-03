package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadAttachmentMetadataImplTest {
    @Test
    fun `cipher-key attachment metadata decrypts with first candidate`() {
        val attachment = attachment(
            fileName = "item:file.pdf",
            keyBase64 = "item:file-key",
        )

        val decrypted = BitwardenCipher.Attachment.decryptMetadata(
            attachment = attachment,
            cryptoCandidates = listOf(
                PrefixBitwardenCr("item").cta(
                    env = itemEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
                PrefixBitwardenCr("global").cta(
                    env = globalEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
            ),
        )

        assertEquals("file.pdf", decrypted.fileName)
        assertEquals("file-key", decrypted.keyBase64)
    }

    @Test
    fun `legacy attachment metadata decrypts with fallback candidate`() {
        val attachment = attachment(
            fileName = "global:file.pdf",
            keyBase64 = "global:file-key",
        )

        val decrypted = BitwardenCipher.Attachment.decryptMetadata(
            attachment = attachment,
            cryptoCandidates = listOf(
                PrefixBitwardenCr("item").cta(
                    env = itemEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
                PrefixBitwardenCr("global").cta(
                    env = globalEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
            ),
        )

        assertEquals("file.pdf", decrypted.fileName)
        assertEquals("file-key", decrypted.keyBase64)
    }
}

private fun attachment(
    fileName: String,
    keyBase64: String,
) = BitwardenCipher.Attachment.Remote(
    id = "attachment-1",
    url = "https://example.com/attachment",
    fileName = fileName,
    keyBase64 = keyBase64,
    size = 1L,
)

private class PrefixBitwardenCr(
    private val prefix: String,
) : BitwardenCr {
    override val base64Service: Base64Service = DownloadAttachmentTestBase64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = { cipher ->
        val prefix = "$prefix:"
        check(cipher.startsWith(prefix)) {
            "Expected cipher text to start with '$prefix'."
        }
        DecodeResult(
            data = cipher.removePrefix(prefix).toByteArray(),
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
    }

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = error("Unused in tests.")

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}

private object DownloadAttachmentTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private val itemEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.CryptoKey(),
)

private val globalEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.UserToken,
)
