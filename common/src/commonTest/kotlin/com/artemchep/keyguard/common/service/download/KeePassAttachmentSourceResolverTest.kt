package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeePassAttachmentSourceResolverTest {
    private val expectedHash = ByteArray(32) { index -> (index * 7).toByte() }
    private val base32Service = RecordingBase32Service(expectedHash)
    private val token = KeePassToken(
        id = ACCOUNT_ID,
        key = KeePassToken.Key(passwordBase64 = ""),
        database = KeePassToken.Database(
            fileName = "vault.kdbx",
            location = FileLocation.Local(
                uri = "file:///vault.kdbx",
                accessToken = null,
                managedByApp = false,
                displayName = "vault.kdbx",
            ),
        ),
    )

    @Test
    fun `resolves current attachment and wipes parsed hash on close`() = runTest {
        val resolver = resolver()

        val resolved = resolver.resolve(request(), source())
        val contentHash = resolved.contentHash

        assertEquals(token, resolved.token)
        assertEquals(ATTACHMENT_SIZE, resolved.expectedSize)
        assertContentEquals(expectedHash, contentHash)
        assertEquals(1, base32Service.decodeCount)

        resolved.close()

        assertTrue(contentHash.all { it == 0.toByte() })
    }

    @Test
    fun `rejects stale cipher revision before parsing hash`() = runTest {
        val resolver = resolver()

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                request = request().copy(remoteCipherId = "stale-remote-id"),
                source = source(),
            )
        }

        assertEquals(0, base32Service.decodeCount)
    }

    @Test
    fun `rejects changed attachment reference and size`() = runTest {
        val resolver = resolver()

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                request = request(),
                source = source().copy(hashRef = "hashref://changed"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                request = request(),
                source = source().copy(expectedSize = ATTACHMENT_SIZE + 1L),
            )
        }

        assertEquals(0, base32Service.decodeCount)
    }

    @Test
    fun `rejects missing account token before parsing hash`() = runTest {
        val resolver = resolver(token = null)

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(request(), source())
        }

        assertEquals(0, base32Service.decodeCount)
    }

    private fun resolver(
        cipher: BitwardenCipher = cipher(),
        token: ServiceToken? = this.token,
    ) = KeePassAttachmentSourceResolverImpl(
        tokenRepository = SingleTokenRepository(token),
        cipherRepository = SingleCipherRepository(cipher),
        base32Service = base32Service,
    )

    private fun request() = DownloadAttachmentRequestData(
        localCipherId = CIPHER_ID,
        remoteCipherId = REMOTE_CIPHER_ID,
        attachmentId = ATTACHMENT_ID,
        source = source(),
        name = "attachment.bin",
        encryptionKey = null,
    )

    private fun source() = DownloadAttachmentRequestData.KeePassSource(
        hashRef = ATTACHMENT_URL,
        expectedSize = ATTACHMENT_SIZE,
    )

    private fun cipher() = BitwardenCipher(
        accountId = ACCOUNT_ID,
        cipherId = CIPHER_ID,
        revisionDate = Instant.fromEpochMilliseconds(1L),
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = REMOTE_CIPHER_ID,
                revisionDate = Instant.fromEpochMilliseconds(1L),
                deletedDate = null,
            ),
        ),
        name = "Cipher",
        notes = null,
        favorite = false,
        attachments = listOf(
            BitwardenCipher.Attachment.Remote(
                id = ATTACHMENT_ID,
                url = ATTACHMENT_URL,
                fileName = "attachment.bin",
                size = ATTACHMENT_SIZE,
            ),
        ),
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.SecureNote,
        secureNote = BitwardenCipher.SecureNote(),
    )

    private companion object {
        const val ACCOUNT_ID = "account"
        const val CIPHER_ID = "cipher"
        const val REMOTE_CIPHER_ID = "remote-cipher"
        const val ATTACHMENT_ID = "attachment"
        const val ATTACHMENT_URL = "hashref://attachment"
        const val ATTACHMENT_SIZE = 1234L
    }
}

private class RecordingBase32Service(
    private val decoded: ByteArray,
) : Base32Service {
    var decodeCount = 0

    override fun encode(bytes: ByteArray): ByteArray = error("Not used by this test")

    override fun decode(bytes: ByteArray): ByteArray {
        decodeCount += 1
        return decoded.copyOf()
    }
}

private class SingleTokenRepository(
    private val token: ServiceToken?,
) : ServiceTokenRepository {
    override fun get(): Flow<List<ServiceToken>> = flowOf(listOfNotNull(token))

    override fun getById(id: AccountId): IO<ServiceToken?> = ioEffect {
        token?.takeIf { it.id == id.id }
    }

    override fun put(model: ServiceToken): IO<Unit> = error("Not used by this test")
}

private class SingleCipherRepository(
    private val cipher: BitwardenCipher?,
) : BitwardenCipherRepository {
    override fun get(): Flow<List<BitwardenCipher>> = flowOf(listOfNotNull(cipher))

    override fun getById(id: String): IO<BitwardenCipher?> = ioEffect {
        cipher?.takeIf { it.cipherId == id }
    }

    override fun put(model: BitwardenCipher): IO<Unit> = error("Not used by this test")
}
