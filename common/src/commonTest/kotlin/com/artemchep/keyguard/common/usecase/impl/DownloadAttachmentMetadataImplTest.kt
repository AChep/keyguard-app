package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.DecodeResult
import com.artemchep.keyguard.test.testBitwardenCr
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
                prefixBitwardenCr("item").cta(
                    env = itemEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
                prefixBitwardenCr("global").cta(
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
                prefixBitwardenCr("item").cta(
                    env = itemEnv,
                    mode = BitwardenCrCta.Mode.DECRYPT,
                ),
                prefixBitwardenCr("global").cta(
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

private fun prefixBitwardenCr(
    prefix: String,
) = testBitwardenCr(
    decoder = {
        { cipher ->
            val cipherPrefix = "$prefix:"
            check(cipher.startsWith(cipherPrefix)) {
                "Expected cipher text to start with '$cipherPrefix'."
            }
            DecodeResult(
                data = cipher.removePrefix(cipherPrefix).toByteArray(),
                type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            )
        }
    },
)

private val itemEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.CryptoKey(),
)

private val globalEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.UserToken,
)
