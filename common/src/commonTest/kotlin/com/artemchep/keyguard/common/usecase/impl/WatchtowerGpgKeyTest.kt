@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialContributions
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialInputContribution
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverLocalKey
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateEvaluator
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
    fun `unchanged certificate is reassessed as restoration becomes effective and expires`() = runTest {
        var revoked = true
        val policy = GpgWatchtowerPolicy(
            parser = object : GpgPublicKeyParser {
                override fun parse(armored: String) = GpgPublicKeyParseResult.Success(
                    listOf(keyInfo(revoked = revoked)),
                )
            },
        )
        val cipher = gpgSecret()

        assertTrue("revoked" in policy.assess(cipher, now)?.unusableIssues.orEmpty())

        revoked = false
        assertEquals(
            emptyList(),
            policy.assess(cipher, Instant.fromEpochSeconds(200))?.unusableIssues,
        )

        revoked = true
        assertTrue(
            "revoked" in policy.assess(cipher, Instant.fromEpochSeconds(300))?.unusableIssues.orEmpty(),
        )
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
        val processor = publishingProcessor(
            keyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(
                states = listOf(
                    DGpgKeyserverState(
                        fingerprint = primaryFingerprint,
                        cipherId = cipherId,
                        verificationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                        publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                        lastCheckedAt = Instant.fromEpochSeconds(0),
                    ),
                ),
            ),
            clock = Clock.System,
        )

        val result = processor.process(listOf(gpgSecret())).single()

        assertEquals("stale", result.value)
        assertEquals(true, result.threat)
    }

    @Test
    fun `publishing processor flags legacy revocation without merging evidence`() = runTest {
        var merges = 0
        val processor = publishingProcessor(
            keyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(
                states = listOf(
                    DGpgKeyserverState(
                        fingerprint = primaryFingerprint,
                        cipherId = cipherId,
                        verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
                    ),
                ),
            ),
            reconciler = object : GpgCertificateMaterialReconciler {
                override fun reconcile(
                    expectedPrimaryFingerprint: String,
                    existingPublicCertificate: String?,
                    existingSecretCertificate: String?,
                    incomingPublicCertificate: String?,
                    incomingSecretCertificate: String?,
                ): GpgCertificateMaterialReconcileResult {
                    merges++
                    error("Legacy revocations do not need evidence reconciliation")
                }
            },
        )

        val result = processor.process(listOf(gpgSecret())).single()

        assertEquals("revoked", result.value)
        assertEquals(true, result.threat)
        assertEquals(0, merges)
    }

    @Test
    fun `daily assessments share evaluations and only change versions when the verdict changes`() = runTest {
        val repository = WatchtowerGpgFakeKeyserverStateRepository(listOf(publishingState()))
        var resolutions = 0
        val processor = publishingProcessor(
            keyserverStateRepository = repository,
            resolver = publishingResolver {
                resolutions++
                if (testScheduler.currentTime < 2.days.inWholeMilliseconds) GpgRevocationStatus.NOT_REVOKED else GpgRevocationStatus.REVOKED
            },
        )
        val versions = mutableListOf<String>()
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            processor.version().collect(versions::add)
        }
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)
        assertEquals(1, resolutions)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, resolutions)
        assertEquals(1, versions.size)

        advanceTimeBy(1.days - 1.minutes)
        runCurrent()
        assertEquals(2, resolutions)
        assertEquals(1, versions.size)

        advanceTimeBy(1.days)
        runCurrent()
        assertEquals("revoked", processor.process(listOf(gpgSecret())).single().value)
        assertEquals(3, resolutions)
        assertEquals(2, versions.size)
        assertEquals(listOf(publishingState()), repository.getAll().first())

        subscription.cancel()
        runCurrent()
        advanceTimeBy(2.days)
        runCurrent()
        assertEquals(3, resolutions)
        assertEquals(0, repository.states.subscriptionCount.value)

        // A new subscription must evaluate now, not replay the previous lifetime's result.
        processor.version().first()
        assertEquals(4, resolutions)
    }

    @Test
    fun `publication becomes stale on the daily assessment during an unchanged subscription`() = runTest {
        val lastChecked = now - (30.days - 1.minutes)
        val processor = publishingProcessor(
            keyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(
                listOf(publishingState().copy(lastCheckedAt = lastChecked)),
            ),
        )
        val versions = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            processor.version().collect(versions::add)
        }
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)
        assertEquals(1, versions.size)

        advanceTimeBy(1.days - 1.minutes)
        runCurrent()

        assertEquals("stale", processor.process(listOf(gpgSecret())).single().value)
        assertEquals(2, versions.size)
    }

    @Test
    fun `evidence changes assess the current time before the next daily check`() = runTest {
        val state = publishingState().copy(lastCheckedAt = now - (30.days - 1.minutes))
        val repository = WatchtowerGpgFakeKeyserverStateRepository(listOf(state))
        val processor = publishingProcessor(keyserverStateRepository = repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { processor.version().collect() }
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)

        advanceTimeBy(1.minutes)
        repository.states.value = listOf(state.copy(revocationEvidenceArmored = "new evidence"))
        runCurrent()

        assertEquals("stale", processor.process(listOf(gpgSecret())).single().value)
    }

    @Test
    fun `revoker changes outside the processing batch trigger immediate reassessment`() = runTest {
        val target = gpgSecret()
        val vault = MutableStateFlow(listOf(target))
        var resolutions = 0
        val processor = publishingProcessor(
            ciphers = vault,
            resolver = publishingResolver { candidates ->
                resolutions++
                if (candidates.any { it.armored == "revoker" }) GpgRevocationStatus.REVOKED else GpgRevocationStatus.NOT_REVOKED
            },
        )
        val versions = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            processor.version().collect(versions::add)
        }
        runCurrent()
        assertNull(processor.process(listOf(target)).single().value)

        val revoker = gpgSecret(publicKeyArmored = " ", fingerprint = " ", metadata = null).copy(
            id = "revoker",
            fields = listOf(
                DSecret.Field(name = GpgAgentFields.PUBLIC_KEY_ARMORED, value = "revoker", type = DSecret.Field.Type.Hidden),
                DSecret.Field(name = GpgAgentFields.FINGERPRINT, value = encryptionSubKeyFingerprint, type = DSecret.Field.Type.Text),
            ),
        )
        vault.value = listOf(target, revoker)
        runCurrent()
        assertEquals("revoked", processor.process(listOf(target)).single().value)

        vault.value = listOf(target, revoker.copy(notes = "An unrelated edit"))
        runCurrent()
        assertEquals(2, resolutions)
        vault.value = listOf(target)
        runCurrent()
        assertNull(processor.process(listOf(target)).single().value)
        assertEquals(3, resolutions)
        assertEquals(3, versions.size)
    }

    @Test
    fun `evidence changes trigger evaluation and inconclusive policy never clears a saved warning`() = runTest {
        val repository = WatchtowerGpgFakeKeyserverStateRepository(listOf(publishingState()))
        var revocation = GpgRevocationStatus.NOT_REVOKED
        val processor = publishingProcessor(
            keyserverStateRepository = repository,
            resolver = publishingResolver { revocation },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { processor.version().collect() }
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)
        revocation = GpgRevocationStatus.INDETERMINATE
        repository.states.value = listOf(publishingState().copy(revocationEvidenceArmored = "new evidence"))
        runCurrent()
        assertEquals("unknown", processor.process(listOf(gpgSecret())).single().value)

        repository.states.value = listOf(publishingState().copy(verificationStatus = GpgKeyserverVerificationStatus.REVOKED))
        runCurrent()
        assertEquals("revoked", processor.process(listOf(gpgSecret())).single().value)
    }

    @Test
    fun `missing and unsupported policy are inconclusive and unbacked warnings cannot clear`() {
        val supported = publishingResolver { GpgRevocationStatus.NOT_REVOKED }
            .resolve(null, "public", primaryFingerprint, emptyList())
        val resolutions = listOf(
            null,
            supported.copy(authorization = supported.authorization.copy(policyRevision = 0)),
            supported.copy(authorization = supported.authorization.copy(revocations = emptyMap())),
        )
        for (resolution in resolutions) {
            val resolver = object : GpgKeyMetadataResolver {
                override fun resolve(
                    privateKeyArmored: String?,
                    publicKeyArmored: String?,
                    fingerprint: String?,
                    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                ) = resolution
            }
            val evaluator = GpgKeyserverStateEvaluator(WatchtowerGpgFakeReconciler, resolver)
            assertEquals(GpgKeyserverVerificationStatus.UNKNOWN, evaluator.evaluate(publishingState(), "public", emptyList()))
            assertEquals(
                GpgKeyserverVerificationStatus.REVOKED,
                evaluator.evaluate(publishingState().copy(verificationStatus = GpgKeyserverVerificationStatus.REVOKED), "public", emptyList()),
            )
        }
        val evaluator = GpgKeyserverStateEvaluator(WatchtowerGpgFakeReconciler, publishingResolver { GpgRevocationStatus.NOT_REVOKED })
        assertEquals(
            GpgKeyserverVerificationStatus.REVOKED,
            evaluator.evaluate(publishingState().copy(hasUnbackedRevocation = true), "public", emptyList()),
        )
    }

    @Test
    fun `an evaluation failure reports unknown and is retried on the next daily assessment`() = runTest {
        var fails = true
        val processor = publishingProcessor(
            resolver = publishingResolver {
                check(!fails) { "Evidence could not be evaluated" }
                GpgRevocationStatus.NOT_REVOKED
            },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { processor.version().collect() }
        runCurrent()
        assertEquals("unknown", processor.process(listOf(gpgSecret())).single().value)

        fails = false
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals("unknown", processor.process(listOf(gpgSecret())).single().value)

        advanceTimeBy(1.days - 1.minutes)
        runCurrent()
        assertNull(processor.process(listOf(gpgSecret())).single().value)
    }
}

private fun TestScope.publishingProcessor(
    keyserverStateRepository: GpgKeyserverStateRepository = WatchtowerGpgFakeKeyserverStateRepository(listOf(publishingState())),
    ciphers: Flow<List<DSecret>> = flowOf(listOf(gpgSecret())),
    reconciler: GpgCertificateMaterialReconciler = WatchtowerGpgFakeReconciler,
    resolver: GpgKeyMetadataResolver = publishingResolver { GpgRevocationStatus.NOT_REVOKED },
    clock: Clock = object : Clock {
        override fun now() = now + testScheduler.currentTime.milliseconds
    },
) = WatchtowerGpgKeyPublishing(
    keyserverStateRepository = keyserverStateRepository,
    getCiphers = object : GetCiphers {
        override fun invoke() = ciphers
    },
    evaluator = GpgKeyserverStateEvaluator(reconciler, resolver),
    scope = backgroundScope,
    clock = clock,
    dispatcher = UnconfinedTestDispatcher(testScheduler),
)

private fun publishingState() = DGpgKeyserverState(
    fingerprint = primaryFingerprint,
    cipherId = cipherId,
    verificationStatus = GpgKeyserverVerificationStatus.VERIFIED,
    publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
    revocationEvidenceArmored = "public",
    lastCheckedAt = now,
    lastRefreshedAt = now,
)

private fun publishingResolver(
    revocation: (List<GpgOpenPgpPublicKey>) -> GpgRevocationStatus,
) = object : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ) = GpgAgentMetadataResolution(
        metadata = gpgMetadata(metadataKey()),
        authorization = GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = 100,
            policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
            keys = emptyList(),
            revocations = mapOf(requireNotNull(fingerprint) to revocation(candidateRevocationKeys)),
        ),
    )
}

internal object WatchtowerGpgFakeReconciler : GpgCertificateMaterialReconciler {
    override fun reconcile(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: String?,
        existingSecretCertificate: String?,
        incomingPublicCertificate: String?,
        incomingSecretCertificate: String?,
    ): GpgCertificateMaterialReconcileResult {
        val absent = GpgCertificateMaterialInputContribution(false, false, false)
        return GpgCertificateMaterialReconcileResult.Success(
            localPublicMaterial = listOfNotNull(existingPublicCertificate, incomingPublicCertificate).distinct().joinToString(),
            localSecretMaterial = null,
            transferablePublicCertificate = incomingPublicCertificate,
            transferableSecretKey = null,
            primaryFingerprint = expectedPrimaryFingerprint,
            contributions = GpgCertificateMaterialContributions(absent, absent, absent, absent),
            withheldReasons = emptySet(),
        )
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
    states: List<DGpgKeyserverState>,
) : GpgKeyserverStateRepository {
    val states = MutableStateFlow(states)
    override fun getAll(): Flow<List<DGpgKeyserverState>> = states

    override fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> = states.map { values ->
        values.firstOrNull {
            it.fingerprint.normalizeGpgFingerprint() == fingerprint.normalizeGpgFingerprint()
        }
    }

    override fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>> = states.map { values -> values.filter { it.cipherId == cipherId } }

    override fun put(
        model: DGpgKeyserverState,
    ): IO<Unit> = ioEffect {
        error("Watchtower must not mutate keyserver state")
    }

    override fun update(
        fingerprint: String,
        transform: (DGpgKeyserverState?, List<GpgKeyserverLocalKey>) -> DGpgKeyserverState,
    ): IO<DGpgKeyserverState> = error("Watchtower must not mutate keyserver evidence")

    override fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit> = ioEffect {
        error("Watchtower must not mutate keyserver state")
    }

    override fun removeAll(): IO<Unit> = ioEffect {
        error("Watchtower must not mutate keyserver state")
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
