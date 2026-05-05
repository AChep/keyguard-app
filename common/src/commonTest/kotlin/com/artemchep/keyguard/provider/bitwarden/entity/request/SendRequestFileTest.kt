package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SendRequestFileTest {
    @Test
    fun `new file send request includes file metadata and encrypted length`() {
        val model = createFileSend(
            service = BitwardenService(),
        )

        val request = with(FakeCryptoGenerator) {
            with(IdentityBase64Service) {
                SendRequest.of(
                    model = model,
                    key = byteArrayOf(1, 2, 3),
                )
            }
        }

        assertEquals("invoice.pdf", request.file?.fileName)
        assertEquals(321L, request.fileLength)
    }

    @Test
    fun `existing remote file send request omits file metadata and encrypted length`() {
        val model = createFileSend(
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = "remote-send-1",
                    revisionDate = TEST_INSTANT,
                    deletedDate = null,
                ),
            ),
        )

        val request = with(FakeCryptoGenerator) {
            with(IdentityBase64Service) {
                SendRequest.of(
                    model = model,
                    key = byteArrayOf(1, 2, 3),
                )
            }
        }

        assertNull(request.file)
        assertNull(request.fileLength)
    }
}

private fun createFileSend(
    service: BitwardenService,
) = BitwardenSend(
    accountId = "account-1",
    sendId = "send-1",
    accessId = "access-1",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = TEST_INSTANT,
    expirationDate = TEST_INSTANT,
    service = service,
    authType = BitwardenSend.AuthType.None,
    keyBase64 = "send-key",
    name = "Quarterly report",
    notes = "Encrypted file send",
    accessCount = 0,
    type = BitwardenSend.Type.File,
    file = BitwardenSend.File(
        id = "file-1",
        fileName = "invoice.pdf",
        size = 123L,
        pendingUpload = PendingUploadFile(
            path = "/tmp/send-1.bin",
            plainSize = 123L,
            encryptedSize = 321L,
        ),
    ),
)

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private object IdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private object FakeCryptoGenerator : CryptoGenerator {
    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = error("unused")

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = error("unused")

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = error("unused")

    override fun seed(length: Int): ByteArray = error("unused")

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = error("unused")

    override fun hashSha1(data: ByteArray): ByteArray = error("unused")

    override fun hashSha256(data: ByteArray): ByteArray = error("unused")

    override fun hashMd5(data: ByteArray): ByteArray = error("unused")

    override fun uuid(): String = error("unused")

    override fun random(): Int = error("unused")

    override fun random(range: IntRange): Int = error("unused")
}
