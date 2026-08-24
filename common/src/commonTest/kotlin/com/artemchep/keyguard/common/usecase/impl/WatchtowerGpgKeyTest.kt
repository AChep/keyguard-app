package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchtowerGpgKeyTest {
    @Test
    fun `malformed public key is unusable`() = runTest {
        val policy = GpgWatchtowerPolicy(
            parser = WatchtowerGpgFakeParser(
                GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed),
            ),
        )

        val result = policy.assess(
            cipher = gpgSecret(),
            now = now,
        )

        assertEquals(
            listOf("malformed_public_key"),
            result?.unusableIssues,
        )
    }

    @Test
    fun `revoked and expired key is unusable`() = runTest {
        val policy = GpgWatchtowerPolicy(
            parser = WatchtowerGpgFakeParser(
                keyInfo(
                    revoked = true,
                    expiresAt = Instant.fromEpochSeconds(10),
                ),
            ),
        )

        val result = policy.assess(
            cipher = gpgSecret(),
            now = now,
        )

        assertTrue("revoked" in result?.unusableIssues.orEmpty())
        assertTrue("expired" in result?.unusableIssues.orEmpty())
    }

    @Test
    fun `missing agent metadata is unusable for private key`() = runTest {
        val policy = GpgWatchtowerPolicy(
            parser = WatchtowerGpgFakeParser(keyInfo()),
        )

        val result = policy.assess(
            cipher = gpgSecret(metadata = null),
            now = now,
        )

        assertTrue("missing_agent_metadata" in result?.unusableIssues.orEmpty())
    }

    @Test
    fun `rsa key below 2048 bits is weak`() = runTest {
        val policy = GpgWatchtowerPolicy(
            parser = WatchtowerGpgFakeParser(
                keyInfo(
                    algorithm = "RSA",
                    bitStrength = 1024,
                ),
            ),
        )

        val result = policy.assess(
            cipher = gpgSecret(),
            now = now,
        )

        assertEquals(
            listOf("rsa_1024"),
            result?.weakIssues,
        )
    }

    @Test
    fun `modern signing and encryption key has no issues`() = runTest {
        val policy = GpgWatchtowerPolicy(
            parser = WatchtowerGpgFakeParser(
                keyInfo(
                    canSign = true,
                    canEncrypt = true,
                    subKeys = listOf(
                        GpgPublicSubKeyInfo(
                            fingerprint = encryptionSubKeyFingerprint,
                            keyId = encryptionSubKeyFingerprint.takeLast(16),
                            algorithm = "X25519",
                            canSign = false,
                            canEncrypt = true,
                            revoked = false,
                            expiresAt = null,
                        ),
                    ),
                ),
            ),
        )

        val result = policy.assess(
            cipher = gpgSecret(
                metadata = gpgMetadata(
                    metadataKey(
                            fingerprint = primaryFingerprint,
                            capabilities = setOf("sign"),
                        ),
                    metadataKey(
                            fingerprint = encryptionSubKeyFingerprint,
                            capabilities = setOf("decrypt"),
                        ),
                ),
            ),
            now = now,
        )

        assertEquals(emptyList(), result?.unusableIssues)
        assertEquals(emptyList(), result?.weakIssues)
    }

    @Test
    fun `publishing processor flags stale verified state`() = runTest {
        val processor = WatchtowerGpgKeyPublishing(
            keyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(
                states = listOf(
                    DGpgKeyserverState(
                        fingerprint = primaryFingerprint,
                        cipherId = cipherId,
                        verificationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                        lastCheckedAt = Instant.fromEpochSeconds(0),
                    ),
                ),
            ),
        )

        val result = processor.process(listOf(gpgSecret())).single()

        assertEquals("stale", result.value)
        assertEquals(true, result.threat)
    }

    @Test
    fun `publishing processor flags revoked keyserver state`() = runTest {
        val processor = WatchtowerGpgKeyPublishing(
            keyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(
                states = listOf(
                    DGpgKeyserverState(
                        fingerprint = primaryFingerprint,
                        cipherId = cipherId,
                        verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
                    ),
                ),
            ),
        )

        val result = processor.process(listOf(gpgSecret())).single()

        assertEquals("revoked", result.value)
        assertEquals(true, result.threat)
    }
}

private class WatchtowerGpgFakeParser(
    private val result: GpgPublicKeyParseResult,
) : GpgPublicKeyParser {
    constructor(
        key: GpgPublicKeyInfo,
    ) : this(GpgPublicKeyParseResult.Success(listOf(key)))

    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult = result
}

private class WatchtowerGpgFakeKeyserverStateRepository(
    private val states: List<DGpgKeyserverState>,
) : GpgKeyserverStateRepository {
    override fun getAll(): Flow<List<DGpgKeyserverState>> = flowOf(states)

    override fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> = flowOf(
        states.firstOrNull {
            it.fingerprint.normalizeGpgFingerprint() == fingerprint.normalizeGpgFingerprint()
        },
    )

    override fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>> = flowOf(
        states.filter { it.cipherId == cipherId },
    )

    override fun put(
        model: DGpgKeyserverState,
    ): IO<Unit> = ioEffect {
        Unit
    }

    override fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit> = ioEffect {
        Unit
    }

    override fun removeAll(): IO<Unit> = ioEffect {
        Unit
    }
}

private fun gpgSecret(
    publicKeyArmored: String? = "public",
    privateKeyArmored: String? = "private",
    fingerprint: String? = primaryFingerprint,
    metadata: GpgAgentKeyMetadata? = gpgMetadata(metadataKey()),
) = DSecret(
    id = cipherId,
    accountId = "account",
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = now,
    createdDate = null,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = "GPG key",
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

private fun metadataKey(
    fingerprint: String = primaryFingerprint,
    capabilities: Set<String> = setOf("sign"),
) = GpgAgentKeyMetadataKey(
    keygrip = "keygrip-$fingerprint",
    fingerprint = fingerprint,
    algorithm = "ED25519",
    capabilities = capabilities,
)

private fun keyInfo(
    fingerprint: String = primaryFingerprint,
    algorithm: String = "ED25519",
    bitStrength: Int? = null,
    revoked: Boolean = false,
    expiresAt: Instant? = null,
    canSign: Boolean = true,
    canEncrypt: Boolean = false,
    subKeys: List<GpgPublicSubKeyInfo> = emptyList(),
) = GpgPublicKeyInfo(
    fingerprint = fingerprint,
    keyId = fingerprint.takeLast(16),
    algorithm = algorithm,
    bitStrength = bitStrength,
    userIds = listOf("Alice Example <alice@example.com>"),
    emails = listOf("alice@example.com"),
    createdAt = null,
    expiresAt = expiresAt,
    revoked = revoked,
    canSign = canSign,
    canEncrypt = canEncrypt,
    publicKeyArmored = "public",
    subKeys = subKeys,
)

private const val cipherId = "cipher"
private const val primaryFingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
private const val encryptionSubKeyFingerprint = "55C9BA78E6D4B1F84467EEC2FBA61A6B8021220A"
private val now = Instant.fromEpochSeconds(100)
