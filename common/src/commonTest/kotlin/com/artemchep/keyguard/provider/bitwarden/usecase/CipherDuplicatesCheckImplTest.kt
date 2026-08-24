package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CipherDuplicatesCheckImplTest {
    private val duplicateChecker = CipherDuplicatesCheckImpl(
        cryptoGenerator = DuplicateTestCryptoGenerator,
        base64Service = DuplicateTestBase64Service,
        similarityService = DuplicateTestSimilarityService,
        logRepository = DuplicateTestLogRepository,
    )

    @Test
    fun `gpg keys with same normalized fingerprint are duplicates despite different names`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "local",
                    name = "Work signing key",
                    fingerprint = "d0bb cfbb 250d 3bb0 658e 5384 f83d 947d 29ef ecf7",
                ),
                gpgSecret(
                    id = "remote",
                    name = "Imported backup",
                    fingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
                ),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(
            setOf("local", "remote"),
            groups.single().ciphers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `gpg keys with same public key are duplicates when fingerprint is missing`() {
        val publicKey = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----
            test-public-key
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent()

        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "First key",
                    fingerprint = null,
                    publicKeyArmored = publicKey,
                ),
                gpgSecret(
                    id = "second",
                    name = "Second key",
                    fingerprint = null,
                    publicKeyArmored = publicKey.replace("\n", "\r\n"),
                ),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(
            setOf("first", "second"),
            groups.single().ciphers.map { it.id }.toSet(),
        )
    }

    @Test
    fun `gpg keys with different fingerprints and same placeholder name are not duplicates`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "GPG key",
                    fingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
                ),
                gpgSecret(
                    id = "second",
                    name = "GPG key",
                    fingerprint = "55C9BA78E6D4B1F84467EEC2FBA61A6B8021220A",
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `derived gpg metadata does not make keys duplicates when key material is missing`() {
        val metadata = gpgMetadata(
            GpgAgentKeyMetadataKey(
                    keygrip = "keygrip-a",
                    fingerprint = "d0bb cfbb 250d 3bb0 658e 5384 f83d 947d 29ef ecf7",
            ),
        )
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "Imported key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                    metadata = metadata,
                ),
                gpgSecret(
                    id = "second",
                    name = "Backup key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                    metadata = metadata,
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    @Test
    fun `gpg keys without key material and same placeholder name are not duplicates`() {
        val groups = duplicatesCheck(
            ciphers = listOf(
                gpgSecret(
                    id = "first",
                    name = "GPG key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                ),
                gpgSecret(
                    id = "second",
                    name = "GPG key",
                    fingerprint = null,
                    privateKeyArmored = null,
                    publicKeyArmored = null,
                ),
            ),
        )

        assertEquals(emptyList(), groups)
    }

    private fun duplicatesCheck(
        ciphers: List<DSecret>,
    ) = duplicateChecker(
        ciphers = ciphers,
        sensitivity = CipherDuplicatesCheck.Sensitivity.NORMAL,
    )
}

private fun gpgSecret(
    id: String,
    name: String,
    privateKeyArmored: String? = "private-$id",
    publicKeyArmored: String? = "public-$id",
    fingerprint: String? = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
    metadata: GpgAgentKeyMetadata? = null,
) = DSecret(
    id = id,
    accountId = "account",
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = name,
    notes = "",
    favorite = false,
    reprompt = false,
    synced = true,
    type = DSecret.Type.GpgKey,
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = metadata,
    ),
)

private object DuplicateTestBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private object DuplicateTestCryptoGenerator : CryptoGenerator {
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

    override fun hashSha256(data: ByteArray): ByteArray = data

    override fun hashMd5(data: ByteArray): ByteArray = error("unused")

    override fun uuid(): String = "duplicate-test-uuid"

    override fun random(): Int = error("unused")

    override fun random(range: IntRange): Int = error("unused")
}

private object DuplicateTestSimilarityService : SimilarityService {
    override fun score(
        a: String,
        b: String,
    ): Float = if (a == b) 1f else 0f
}

private object DuplicateTestLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
