package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class CipherMappingTest {
    @Test
    fun `login uri signatures map to domain model`() = runTest {
        val fingerprint = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
        val cipher = BitwardenCipher(
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
            name = "Android Login",
            notes = "",
            favorite = false,
            reprompt = BitwardenCipher.RepromptType.None,
            type = BitwardenCipher.Type.Login,
            login = BitwardenCipher.Login(
                uris = listOf(
                    BitwardenCipher.Login.Uri(
                        uri = "androidapp://com.example.app",
                        signatures = listOf(
                            BitwardenCipher.Login.Uri.Signature(
                                certFingerprintSha256 = fingerprint,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val domain = cipher.toDomain(
            getPasswordStrength = fakeGetPasswordStrength,
        )

        assertEquals(fingerprint, domain.uris.single().signatures.single().certFingerprintSha256)
    }

    @Test
    fun `pending local attachment makes cipher unsynced without exposing staged upload metadata`() = runTest {
        val pendingUpload = PendingUploadFile(
            path = "/tmp/cipher-1.attachment-1.bin",
            plainSize = 123L,
            encryptedSize = 321L,
        )
        val cipher = BitwardenCipher(
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
            attachments = listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment-1",
                    url = "file:///tmp/invoice.pdf",
                    fileName = "invoice.pdf",
                    size = 123L,
                    keyBase64 = "attachment-key",
                    pendingUpload = pendingUpload,
                ),
            ),
            reprompt = BitwardenCipher.RepromptType.None,
            type = BitwardenCipher.Type.SecureNote,
            secureNote = BitwardenCipher.SecureNote(),
        )

        val domain = cipher.toDomain(
            getPasswordStrength = fakeGetPasswordStrength,
        )

        assertFalse(domain.synced)
        val attachment = assertIs<com.artemchep.keyguard.common.model.DSecret.Attachment.Local>(
            domain.attachments.single(),
        )
        assertEquals("file:///tmp/invoice.pdf", attachment.url)
        assertEquals("invoice.pdf", attachment.fileName)
        assertEquals("attachment-key", attachment.keyBase64)
        assertEquals(123L, attachment.size)
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private val fakeGetPasswordStrength = object : GetPasswordStrength {
    override fun invoke(password: String): IO<PasswordStrength> = ioEffect {
        error("unused in cipher mapping tests")
    }
}
