package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class BackupAttachmentFingerprintTest {
    @Test
    fun `url changes do not invalidate attachment fingerprint`() {
        val crypto = StableCryptoGenerator()
        val cipher = cipher()
        val attachment = attachment(url = "https://example.com/one")

        val first = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment,
            cryptoGenerator = crypto,
        )
        val second = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment.copy(url = "https://example.com/two"),
            cryptoGenerator = crypto,
        )

        assertEquals(first, second)
    }

    @Test
    fun `attachment key changes do not invalidate attachment fingerprint`() {
        val crypto = StableCryptoGenerator()
        val cipher = cipher()
        val attachment = attachment()

        val first = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment,
            cryptoGenerator = crypto,
        )
        val second = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment.copy(keyBase64 = "key-2"),
            cryptoGenerator = crypto,
        )

        assertEquals(first, second)
    }

    @Test
    fun `stable metadata changes invalidate attachment fingerprint`() {
        val crypto = StableCryptoGenerator()
        val cipher = cipher()
        val attachment = attachment()
        val original = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment,
            cryptoGenerator = crypto,
        )

        assertNotEquals(
            original,
            BackupAttachmentFingerprint.remote(
                cipher = cipher.copy(accountId = "account-2"),
                attachment = attachment,
                cryptoGenerator = crypto,
            ),
        )
        assertNotEquals(
            original,
            BackupAttachmentFingerprint.remote(
                cipher = cipher,
                attachment = attachment.copy(size = 2L),
                cryptoGenerator = crypto,
            ),
        )
        assertNotEquals(
            original,
            BackupAttachmentFingerprint.remote(
                cipher = cipher.copy(id = "local-cipher-2"),
                attachment = attachment,
                cryptoGenerator = crypto,
            ),
        )
        assertNotEquals(
            original,
            BackupAttachmentFingerprint.remote(
                cipher = cipher,
                attachment = attachment.copy(remoteCipherId = "remote-cipher-2"),
                cryptoGenerator = crypto,
            ),
        )
        assertNotEquals(
            original,
            BackupAttachmentFingerprint.remote(
                cipher = cipher,
                attachment = attachment.copy(id = "attachment-2"),
                cryptoGenerator = crypto,
            ),
        )
    }

    private fun cipher() = DSecret(
        id = "local-cipher-1",
        accountId = "account-1",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.fromEpochMilliseconds(1L),
        createdDate = null,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = "remote-cipher-1",
                revisionDate = Instant.fromEpochMilliseconds(1L),
                deletedDate = null,
            ),
        ),
        name = "Cipher",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.None,
    )

    private fun attachment(
        url: String? = "https://example.com/file",
    ) = DSecret.Attachment.Remote(
        id = "attachment-1",
        url = url,
        remoteCipherId = "remote-cipher-1",
        fileName = "file.txt",
        keyBase64 = "key-1",
        size = 1L,
    )
}

private class StableCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = seed

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = seed

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = seed

    override fun seed(length: Int): ByteArray = ByteArray(length)

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = key + data

    override fun hashSha1(data: ByteArray): ByteArray = data

    override fun hashSha256(data: ByteArray): ByteArray = data

    override fun hashMd5(data: ByteArray): ByteArray = data

    override fun uuid(): String = "uuid"

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = range.first
}
