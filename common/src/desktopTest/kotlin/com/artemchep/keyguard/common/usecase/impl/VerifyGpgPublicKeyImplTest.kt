package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.VerifyGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverLocalKey
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PRIMARY_FINGERPRINT
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.provider.bitwarden.usecase.refreshRevocationCertificates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val PRIMARY_FINGERPRINT = GPG_TEST_CV25519_PRIMARY_FINGERPRINT
private const val OTHER_FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
private const val CIPHER_ID = "cipher-id"
private const val ACCOUNT_ID = "account-id"
private val instant: Instant = Instant.parse("2024-01-01T00:00:00Z")
private val refreshedAt: Instant = Instant.parse("2024-02-01T00:00:00Z")

class VerifyGpgPublicKeyImplTest {
    private companion object {
        const val primaryFingerprint = PRIMARY_FINGERPRINT
        const val otherFingerprint = OTHER_FINGERPRINT
        const val cipherId = CIPHER_ID
        const val accountId = ACCOUNT_ID
    }

    @Test
    fun `invoke verifies by email and persists verified state`() = runTest {
        val client = FakeKeyserverClient(
            byEmail = mapOf(
                "alice@example.com" to listOf(
                    DGpgKeyserverResult(
                        fingerprint = primaryFingerprint.lowercase(),
                        emails = listOf("alice@example.com"),
                        publicKeyArmored = GPG_TEST_CV25519_PUBLIC_KEY,
                        sourceKeyserver = GpgKeyserverConfig.DEFAULT_URL,
                    ),
                ),
            ),
        )
        val repository = FakeGpgKeyserverStateRepository()
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret()),
            client = client,
            repository = repository,
            parser = FakeParser(
                key = keyInfo(
                    fingerprint = primaryFingerprint.lowercase(),
                    emails = listOf("alice@example.com"),
                ),
            ),
        )

        val result = useCase(
            VerifyGpgPublicKeyRequest(
                cipherId = cipherId,
                accountId = accountId,
            ),
        ).bind()

        assertEquals(primaryFingerprint, result.fingerprint)
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, result.overall)
        assertEquals(
            mapOf("alice@example.com" to GpgKeyserverVerificationStatus.VERIFIED),
            result.perEmail,
        )
        assertEquals(listOf("alice@example.com"), client.byEmailCalls)
        assertEquals(emptyList(), client.byFingerprintCalls)

        val saved = assertNotNull(repository.saved[primaryFingerprint])
        assertEquals(cipherId, saved.cipherId)
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, saved.verificationStatus)
        assertNotNull(saved.lastCheckedAt)
        assertNull(saved.lastRefreshedAt)
        assertEquals(GpgKeyserverConfig.DEFAULT_URL, saved.sourceKeyserver)
    }

    @Test
    fun `invoke falls back to fingerprint lookup when emails are not verified`() = runTest {
        val current = DGpgKeyserverState(
            fingerprint = primaryFingerprint,
            cipherId = cipherId,
            lastRefreshedAt = refreshedAt,
            sourceKeyserver = "https://previous.example",
        )
        val client = FakeKeyserverClient(
            byEmail = mapOf(
                "alice@example.com" to listOf(
                    DGpgKeyserverResult(
                        fingerprint = otherFingerprint,
                        emails = listOf("alice@example.com"),
                    ),
                ),
            ),
            byFingerprint = mapOf(
                primaryFingerprint to DGpgKeyserverResult(
                    fingerprint = primaryFingerprint,
                    publicKeyArmored = GPG_TEST_CV25519_PUBLIC_KEY,
                    sourceKeyserver = "https://keyserver.ubuntu.com",
                ),
            ),
        )
        val repository = FakeGpgKeyserverStateRepository(current)
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret()),
            config = GpgKeyserverConfig(
                url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                protocol = GpgKeyserverConfig.Protocol.HKP,
            ),
            client = client,
            repository = repository,
            parser = FakeParser(
                key = keyInfo(
                    fingerprint = primaryFingerprint,
                    emails = listOf("alice@example.com"),
                ),
            ),
        )

        val result = useCase(
            VerifyGpgPublicKeyRequest(
                cipherId = cipherId,
                accountId = accountId,
            ),
        ).bind()

        assertEquals(GpgKeyserverVerificationStatus.FOUND_UNVERIFIED, result.overall)
        assertEquals(
            mapOf("alice@example.com" to GpgKeyserverVerificationStatus.NOT_FOUND),
            result.perEmail,
        )
        assertEquals(listOf("alice@example.com"), client.byEmailCalls)
        assertEquals(listOf(primaryFingerprint), client.byFingerprintCalls)

        val saved = assertNotNull(repository.saved[primaryFingerprint])
        assertEquals(GpgKeyserverVerificationStatus.FOUND_UNVERIFIED, saved.verificationStatus)
        assertEquals(refreshedAt, saved.lastRefreshedAt)
        assertEquals("https://keyserver.ubuntu.com", saved.sourceKeyserver)
    }

    @Test
    fun `verification retains local revocation evidence observed before a concurrent edit`() = runTest {
        val certificates = refreshRevocationCertificates()
        val repository = FakeGpgKeyserverStateRepository()
        val client = FakeKeyserverClient(
            byFingerprint = mapOf(
                certificates.fingerprint to DGpgKeyserverResult(
                    certificates.fingerprint,
                    publicKeyArmored = certificates.restored,
                ),
            ),
        )
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret(certificates.fingerprint, certificates.compromised)),
            client = client,
            repository = repository,
            parser = FakeParser(keyInfo(certificates.fingerprint, emptyList())),
        )
        client.beforeFingerprintLookup = { repository.localKeys = emptyList() }

        val result = useCase(VerifyGpgPublicKeyRequest(cipherId, accountId)).bind()

        assertEquals(GpgKeyserverVerificationStatus.REVOKED, result.overall)
        val state = assertNotNull(repository.saved[certificates.fingerprint])
        assertNotNull(state.revocationEvidenceArmored)
        assertNull(state.cipherId)
    }

    @Test
    fun `an index email match fetches signed evidence and ignores its revoked flag`() = runTest {
        val client = FakeKeyserverClient(
            byEmail = mapOf(
                "alice@example.com" to listOf(DGpgKeyserverResult(primaryFingerprint, revoked = true)),
            ),
            byFingerprint = mapOf(
                primaryFingerprint to DGpgKeyserverResult(
                    primaryFingerprint,
                    publicKeyArmored = GPG_TEST_CV25519_PUBLIC_KEY,
                ),
            ),
        )
        val repository = FakeGpgKeyserverStateRepository()
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret()),
            client = client,
            repository = repository,
            parser = FakeParser(keyInfo(primaryFingerprint, listOf("alice@example.com"))),
        )

        val result = useCase(VerifyGpgPublicKeyRequest(cipherId, accountId)).bind()

        assertEquals(listOf(primaryFingerprint), client.byFingerprintCalls)
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, result.overall)
        assertNotNull(repository.saved[primaryFingerprint]?.revocationEvidenceArmored)
    }

    @Test
    fun `an index match without a retrievable certificate cannot overwrite saved state`() = runTest {
        val initial = DGpgKeyserverState(
            fingerprint = primaryFingerprint,
            verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
        )
        val repository = FakeGpgKeyserverStateRepository(initial)
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret()),
            client = FakeKeyserverClient(
                byEmail = mapOf("alice@example.com" to listOf(DGpgKeyserverResult(primaryFingerprint))),
            ),
            repository = repository,
            parser = FakeParser(keyInfo(primaryFingerprint, listOf("alice@example.com"))),
        )

        assertFailsWith<IllegalStateException> {
            useCase(VerifyGpgPublicKeyRequest(cipherId, accountId)).bind()
        }
        assertEquals(initial, repository.saved[primaryFingerprint])
    }

    @Test
    fun `a legacy revocation stays opaque after later evidence is retained`() = runTest {
        val repository = FakeGpgKeyserverStateRepository(
            DGpgKeyserverState(
                fingerprint = primaryFingerprint,
                verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
            ),
        )
        val useCase = createUseCase(
            ciphers = listOf(createGpgSecret()),
            client = FakeKeyserverClient(
                byEmail = mapOf(
                    "alice@example.com" to listOf(
                        DGpgKeyserverResult(primaryFingerprint, publicKeyArmored = GPG_TEST_CV25519_PUBLIC_KEY),
                    ),
                ),
            ),
            repository = repository,
            parser = FakeParser(keyInfo(primaryFingerprint, listOf("alice@example.com"))),
        )

        repeat(2) {
            val result = useCase(VerifyGpgPublicKeyRequest(cipherId, accountId)).bind()
            assertEquals(GpgKeyserverVerificationStatus.REVOKED, result.overall)
            assertEquals(GpgKeyserverVerificationStatus.REVOKED, result.perEmail["alice@example.com"])
            val state = assertNotNull(repository.saved[primaryFingerprint])
            assertTrue(state.hasUnbackedRevocation)
            assertNotNull(state.revocationEvidenceArmored)
        }
    }

    @Test
    fun `email verification matches normalized fingerprint`() {
        val status = gpgKeyserverEmailVerificationStatus(
            fingerprint = primaryFingerprint.lowercase(),
            results = listOf(
                DGpgKeyserverResult(
                    fingerprint = primaryFingerprint,
                    emails = listOf("alice@example.com"),
                ),
            ),
        )

        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, status)
    }

    @Test
    fun `email verification ignores unsigned server revocation flags`() {
        val status = gpgKeyserverEmailVerificationStatus(
            fingerprint = primaryFingerprint,
            results = listOf(
                DGpgKeyserverResult(
                    fingerprint = primaryFingerprint,
                    emails = listOf("alice@example.com"),
                    revoked = true,
                ),
            ),
        )

        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, status)
    }

    @Test
    fun `email verification reports not found for different fingerprint`() {
        val status = gpgKeyserverEmailVerificationStatus(
            fingerprint = primaryFingerprint,
            results = listOf(
                DGpgKeyserverResult(
                    fingerprint = otherFingerprint,
                    emails = listOf("alice@example.com"),
                ),
            ),
        )

        assertEquals(GpgKeyserverVerificationStatus.NOT_FOUND, status)
    }

}

class GpgPublicKeyVerificationStatusTest {
    private companion object {
        const val primaryFingerprint = PRIMARY_FINGERPRINT
    }


    @Test
    fun `aggregate status prefers verified email match`() {
        val status = gpgKeyserverAggregateVerificationStatus(
            perEmail = listOf(
                GpgKeyserverVerificationStatus.NOT_FOUND,
                GpgKeyserverVerificationStatus.VERIFIED,
            ),
            fingerprintResult = null,
        )

        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, status)
    }

    @Test
    fun `aggregate status uses fingerprint lookup as found unverified fallback`() {
        val status = gpgKeyserverAggregateVerificationStatus(
            perEmail = listOf(GpgKeyserverVerificationStatus.NOT_FOUND),
            fingerprintResult = DGpgKeyserverResult(
                fingerprint = primaryFingerprint,
            ),
        )

        assertEquals(GpgKeyserverVerificationStatus.FOUND_UNVERIFIED, status)
    }

    @Test
    fun `aggregate status reports not found when email and fingerprint lookups miss`() {
        val status = gpgKeyserverAggregateVerificationStatus(
            perEmail = listOf(GpgKeyserverVerificationStatus.NOT_FOUND),
            fingerprintResult = null,
        )

        assertEquals(GpgKeyserverVerificationStatus.NOT_FOUND, status)
    }

}

private fun createUseCase(
    ciphers: List<DSecret>,
    config: GpgKeyserverConfig = GpgKeyserverConfig(),
    client: GpgKeyserverClient,
    repository: FakeGpgKeyserverStateRepository,
    parser: GpgPublicKeyParser,
) = VerifyGpgPublicKeyImpl(
    getCiphers = object : GetCiphers {
        override fun invoke(): Flow<List<DSecret>> = flowOf(ciphers)
    },
    getGpgKeyserverConfig = object : GetGpgKeyserverConfig {
        override fun invoke(): Flow<GpgKeyserverConfig> = flowOf(config)
    },
    keyserverClient = client,
    keyserverStateRepository = repository.apply {
        localKeys = ciphers.map { cipher ->
            GpgKeyserverLocalKey(
                cipherId = cipher.id,
                fingerprint = cipher.fields.first { it.name == GpgAgentFields.FINGERPRINT }.value,
                publicKeyArmored = cipher.fields.first { it.name == GpgAgentFields.PUBLIC_KEY_ARMORED }.value!!,
            )
        }
    },
    parser = parser,
    metadataResolver = NativeGpgKeyMetadataResolver,
    reconciler = NativeGpgCertificateMaterialReconciler,
)

private fun createGpgSecret(
    fingerprint: String = PRIMARY_FINGERPRINT,
    publicKey: String = GPG_TEST_CV25519_PUBLIC_KEY,
) = DSecret(
    id = CIPHER_ID,
    accountId = ACCOUNT_ID,
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = instant,
    createdDate = instant,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = "GPG key",
    notes = "",
    favorite = false,
    reprompt = false,
    synced = true,
    fields = listOf(
        DSecret.Field(
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            value = publicKey,
            type = DSecret.Field.Type.Hidden,
        ),
        DSecret.Field(
            name = GpgAgentFields.FINGERPRINT,
            value = fingerprint,
            type = DSecret.Field.Type.Text,
        ),
    ),
    type = DSecret.Type.SecureNote,
)

private class FakeParser(
    private val key: GpgPublicKeyInfo,
) : GpgPublicKeyParser {
    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult = GpgPublicKeyParseResult.Success(
        keys = listOf(key),
    )
}

private class FakeKeyserverClient(
    private val byEmail: Map<String, List<DGpgKeyserverResult>> = emptyMap(),
    private val byFingerprint: Map<String, DGpgKeyserverResult?> = emptyMap(),
) : GpgKeyserverClient {
    val byEmailCalls = mutableListOf<String>()
    val byFingerprintCalls = mutableListOf<String>()
    var beforeFingerprintLookup: () -> Unit = {}

    override fun search(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect {
        error("Search is not used by verification.")
    }

    override fun canServeSearch(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): Boolean = error("Search is not used by verification.")

    override fun getByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverResult?> = ioEffect {
        val normalized = fingerprint.normalizeGpgFingerprint()
        byFingerprintCalls += normalized
        beforeFingerprintLookup()
        byFingerprint[normalized]
    }

    override fun getByEmail(
        email: String,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect {
        byEmailCalls += email
        byEmail[email].orEmpty()
    }

    override fun upload(
        publicKeyArmored: String,
        config: GpgKeyserverConfig,
    ): IO<Unit> = ioEffect {
        error("Upload is not used by verification.")
    }
}

private class FakeGpgKeyserverStateRepository(
    vararg initial: DGpgKeyserverState,
) : GpgKeyserverStateRepository {
    var localKeys: List<GpgKeyserverLocalKey> = emptyList()
    val saved = initial.associateBy { it.fingerprint.normalizeGpgFingerprint() }
        .toMutableMap()

    override fun getAll(): Flow<List<DGpgKeyserverState>> =
        flowOf(saved.values.toList())

    override fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> =
        flowOf(saved[fingerprint.normalizeGpgFingerprint()])

    override fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>> =
        flowOf(saved.values.filter { it.cipherId == cipherId })

    override fun put(
        model: DGpgKeyserverState,
    ): IO<Unit> = ioEffect {
        saved[model.fingerprint.normalizeGpgFingerprint()] = model.copy(
            fingerprint = model.fingerprint.normalizeGpgFingerprint(),
        )
    }

    override fun update(
        fingerprint: String,
        transform: (DGpgKeyserverState?, List<GpgKeyserverLocalKey>) -> DGpgKeyserverState,
    ): IO<DGpgKeyserverState> = ioEffect {
        val normalized = fingerprint.normalizeGpgFingerprint()
        transform(saved[normalized], localKeys).also { saved[normalized] = it }
    }

    override fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit> = ioEffect {
        saved.remove(fingerprint.normalizeGpgFingerprint())
    }

    override fun removeAll(): IO<Unit> = ioEffect {
        saved.clear()
    }
}

private fun keyInfo(
    fingerprint: String,
    emails: List<String>,
) = GpgPublicKeyInfo(
    fingerprint = fingerprint,
    keyId = fingerprint.takeLast(16),
    algorithm = "ED25519",
    bitStrength = null,
    userIds = emails.map { email -> "Alice Example <$email>" },
    emails = emails,
    createdAt = null,
    expiresAt = null,
    revoked = false,
    canSign = true,
    canEncrypt = false,
    publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
    subKeys = emptyList(),
)
