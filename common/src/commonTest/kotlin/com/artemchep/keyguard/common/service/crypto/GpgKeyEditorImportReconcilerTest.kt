package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.test.generatedGpgKey
import com.artemchep.keyguard.test.gpgCanonicalMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GpgKeyEditorImportReconcilerTest {
    @Test
    fun `reconciliation persists local material and rebuilds all derived fields`() {
        val materialReconciler = RecordingMaterialReconciler(successResult())
        var metadataRequest: MetadataRequest? = null
        val reconciler = GpgKeyEditorImportReconciler(
            materialReconciler = materialReconciler,
            metadataResolver = metadataResolver { privateKey, publicKey, fingerprint ->
                metadataRequest = MetadataRequest(privateKey, publicKey, fingerprint)
                metadataResolution()
            },
            publicKeyParser = publicKeyParser(
                publicKeyInfo(
                    userIds = listOf("Merged User <merged@example.com>"),
                    algorithm = "Ed25519",
                ),
            ),
        )

        val result = assertIs<GpgKeyEditorImportResult.Success>(
            reconciler(
                existing = key(
                    privateKey = "EXISTING SECRET",
                    publicKey = "EXISTING PUBLIC",
                    fingerprint = "STALE STORED FINGERPRINT",
                ),
                incoming = key(
                    privateKey = "INCOMING SECRET",
                    publicKey = "INCOMING PUBLIC",
                ),
            ),
        )

        assertEquals(TEST_FINGERPRINT, materialReconciler.request?.expectedFingerprint)
        assertEquals("EXISTING PUBLIC", materialReconciler.request?.existingPublic)
        assertEquals("EXISTING SECRET", materialReconciler.request?.existingSecret)
        assertEquals("INCOMING PUBLIC", materialReconciler.request?.incomingPublic)
        assertEquals("INCOMING SECRET", materialReconciler.request?.incomingSecret)
        assertEquals(
            MetadataRequest("LOCAL SECRET", "LOCAL PUBLIC", TEST_FINGERPRINT),
            metadataRequest,
        )
        assertEquals("LOCAL SECRET", result.key.privateKeyArmored)
        assertEquals("LOCAL PUBLIC", result.key.publicKeyArmored)
        assertEquals(TEST_FINGERPRINT, result.key.fingerprint)
        assertEquals(TEST_METADATA, result.key.metadata)
        assertEquals("Merged User <merged@example.com>", result.key.userId)
        assertEquals("Ed25519", result.key.typeLabel)
    }

    @Test
    fun `blank editor material is omitted from the native request`() {
        val materialReconciler = RecordingMaterialReconciler(
            successResult(localSecret = null),
        )
        val reconciler = GpgKeyEditorImportReconciler(
            materialReconciler = materialReconciler,
            metadataResolver = metadataResolver { _, _, _ -> metadataResolution() },
            publicKeyParser = publicKeyParser(publicKeyInfo()),
        )

        assertIs<GpgKeyEditorImportResult.Success>(
            reconciler(
                existing = key(privateKey = " \n", publicKey = ""),
                incoming = key(privateKey = "", publicKey = "INCOMING PUBLIC"),
            ),
        )

        assertNull(materialReconciler.request?.existingPublic)
        assertNull(materialReconciler.request?.existingSecret)
        assertNull(materialReconciler.request?.incomingSecret)
    }

    @Test
    fun `metadata and public info must be rebuilt from reconciled material`() {
        val missingMetadata = GpgKeyEditorImportReconciler(
            materialReconciler = RecordingMaterialReconciler(successResult()),
            metadataResolver = metadataResolver { _, _, _ -> null },
            publicKeyParser = publicKeyParser(publicKeyInfo()),
        )
        val nonCanonicalMetadata = GpgKeyEditorImportReconciler(
            materialReconciler = RecordingMaterialReconciler(successResult()),
            metadataResolver = metadataResolver { _, _, _ ->
                metadataResolution(GpgAgentKeyMetadata())
            },
            publicKeyParser = publicKeyParser(publicKeyInfo()),
        )
        val missingPublicInfo = GpgKeyEditorImportReconciler(
            materialReconciler = RecordingMaterialReconciler(successResult()),
            metadataResolver = metadataResolver { _, _, _ -> metadataResolution() },
            publicKeyParser = publicKeyParser(null),
        )
        val existing = key(privateKey = "EXISTING SECRET", publicKey = "EXISTING PUBLIC")
        val incoming = key(privateKey = "INCOMING SECRET", publicKey = "INCOMING PUBLIC")

        assertEquals(
            GpgKeyEditorImportError.MetadataRebuildFailed,
            assertIs<GpgKeyEditorImportResult.Error>(missingMetadata(existing, incoming)).reason,
        )
        assertEquals(
            GpgKeyEditorImportError.MetadataRebuildFailed,
            assertIs<GpgKeyEditorImportResult.Error>(
                nonCanonicalMetadata(existing, incoming),
            ).reason,
        )
        assertEquals(
            GpgKeyEditorImportError.InvalidRebuiltMaterial,
            assertIs<GpgKeyEditorImportResult.Error>(missingPublicInfo(existing, incoming)).reason,
        )
    }

    @Test
    fun `unexpected secret loss and fingerprint changes reject rebuilt output`() {
        val existing = key(privateKey = "EXISTING SECRET", publicKey = "EXISTING PUBLIC")
        val incoming = key(privateKey = "", publicKey = "INCOMING PUBLIC")
        listOf(
            successResult(localSecret = null),
            successResult(primaryFingerprint = "B".repeat(40)),
        ).forEach { nativeResult ->
            val reconciler = GpgKeyEditorImportReconciler(
                materialReconciler = RecordingMaterialReconciler(nativeResult),
                metadataResolver = metadataResolver { _, _, _ -> metadataResolution() },
                publicKeyParser = publicKeyParser(publicKeyInfo()),
            )

            assertEquals(
                GpgKeyEditorImportError.InvalidRebuiltMaterial,
                assertIs<GpgKeyEditorImportResult.Error>(reconciler(existing, incoming)).reason,
            )
        }
    }

    @Test
    fun `typed native failures map to editor import failures`() {
        val cases = listOf(
            invalidInputs(existingPublic = GpgCertificateMaterialInputError.MalformedCertificate) to
                GpgKeyEditorImportError.ExistingMaterialInvalid,
            invalidInputs(incomingSecret = GpgCertificateMaterialInputError.MalformedCertificate) to
                GpgKeyEditorImportError.IncomingMaterialInvalid,
            invalidInputs(existingPublic = GpgCertificateMaterialInputError.FingerprintMismatch) to
                GpgKeyEditorImportError.FingerprintMismatch,
            invalidInputs(incomingPublic = GpgCertificateMaterialInputError.UnsupportedKeyVersion) to
                GpgKeyEditorImportError.UnsupportedMaterial,
            GpgCertificateMaterialReconcileFailure.Pair(
                GpgCertificateMaterialPairError.ConflictingSecretMaterial,
            ) to GpgKeyEditorImportError.ConflictingSecretMaterial,
            GpgCertificateMaterialReconcileFailure.Pair(
                GpgCertificateMaterialPairError.InvalidRebuiltOutput,
            ) to GpgKeyEditorImportError.InvalidRebuiltMaterial,
            GpgCertificateMaterialReconcileFailure.Operational(
                GpgCertificateMaterialOperationalError.ResourceLimit,
            ) to GpgKeyEditorImportError.ResourceLimit,
        )
        cases.forEach { (failure, expected) ->
            val reconciler = GpgKeyEditorImportReconciler(
                materialReconciler = RecordingMaterialReconciler(
                    GpgCertificateMaterialReconcileResult.Error(failure),
                ),
                metadataResolver = metadataResolver { _, _, _ -> metadataResolution() },
                publicKeyParser = publicKeyParser(publicKeyInfo()),
            )

            assertEquals(
                expected,
                assertIs<GpgKeyEditorImportResult.Error>(
                    reconciler(
                        existing = key(publicKey = "EXISTING PUBLIC"),
                        incoming = key(publicKey = "INCOMING PUBLIC"),
                    ),
                ).reason,
            )
        }
    }

    @Test
    fun `unexpected reconciler exceptions become typed failures`() {
        val reconciler = GpgKeyEditorImportReconciler(
            materialReconciler = object : GpgCertificateMaterialReconciler {
                override fun reconcile(
                    expectedPrimaryFingerprint: String,
                    existingPublicCertificate: String?,
                    existingSecretCertificate: String?,
                    incomingPublicCertificate: String?,
                    incomingSecretCertificate: String?,
                ): GpgCertificateMaterialReconcileResult = error("boom")
            },
            metadataResolver = metadataResolver { _, _, _ -> metadataResolution() },
            publicKeyParser = publicKeyParser(publicKeyInfo()),
        )

        assertEquals(
            GpgKeyEditorImportError.UnexpectedFailure,
            assertIs<GpgKeyEditorImportResult.Error>(
                reconciler(
                    existing = key(publicKey = "EXISTING PUBLIC"),
                    incoming = key(publicKey = "INCOMING PUBLIC"),
                ),
            ).reason,
        )
    }

    private class RecordingMaterialReconciler(
        private val result: GpgCertificateMaterialReconcileResult,
    ) : GpgCertificateMaterialReconciler {
        var request: ReconcileRequest? = null

        override fun reconcile(
            expectedPrimaryFingerprint: String,
            existingPublicCertificate: String?,
            existingSecretCertificate: String?,
            incomingPublicCertificate: String?,
            incomingSecretCertificate: String?,
        ): GpgCertificateMaterialReconcileResult {
            request = ReconcileRequest(
                expectedFingerprint = expectedPrimaryFingerprint,
                existingPublic = existingPublicCertificate,
                existingSecret = existingSecretCertificate,
                incomingPublic = incomingPublicCertificate,
                incomingSecret = incomingSecretCertificate,
            )
            return result
        }
    }

    private data class ReconcileRequest(
        val expectedFingerprint: String,
        val existingPublic: String?,
        val existingSecret: String?,
        val incomingPublic: String?,
        val incomingSecret: String?,
    )

    private data class MetadataRequest(
        val privateKey: String?,
        val publicKey: String?,
        val fingerprint: String?,
    )
}

private const val TEST_FINGERPRINT = "A1B2C3D4E5F60718293A4B5C6D7E8F9012345678"

private val TEST_METADATA = gpgCanonicalMetadata(
    fingerprint = TEST_FINGERPRINT,
    keygrip = "test-keygrip",
)

private fun key(
    privateKey: String = "",
    publicKey: String = "PUBLIC",
    fingerprint: String = TEST_FINGERPRINT,
) = generatedGpgKey(
    privateKey = privateKey,
    publicKey = publicKey,
    fingerprint = fingerprint,
    metadata = null,
    userId = "stale user",
    typeLabel = "stale type",
)

private fun successResult(
    localSecret: String? = "LOCAL SECRET",
    primaryFingerprint: String = TEST_FINGERPRINT,
) = GpgCertificateMaterialReconcileResult.Success(
    localPublicMaterial = "LOCAL PUBLIC",
    localSecretMaterial = localSecret,
    transferablePublicCertificate = "TRANSFERABLE PUBLIC",
    transferableSecretKey = localSecret?.let { "TRANSFERABLE SECRET" },
    primaryFingerprint = primaryFingerprint,
    contributions = GpgCertificateMaterialContributions(
        existingPublic = emptyContribution(),
        incomingPublic = emptyContribution(),
        existingSecret = emptyContribution(),
        incomingSecret = emptyContribution(),
    ),
    withheldReasons = emptySet(),
)

private fun emptyContribution() = GpgCertificateMaterialInputContribution(
    present = false,
    uniquePublicEvidence = false,
    uniqueSecretCapability = false,
)

private fun invalidInputs(
    existingPublic: GpgCertificateMaterialInputError? = null,
    incomingPublic: GpgCertificateMaterialInputError? = null,
    existingSecret: GpgCertificateMaterialInputError? = null,
    incomingSecret: GpgCertificateMaterialInputError? = null,
) = GpgCertificateMaterialReconcileFailure.InvalidInputs(
    existingPublic = existingPublic,
    incomingPublic = incomingPublic,
    existingSecret = existingSecret,
    incomingSecret = incomingSecret,
)

private fun metadataResolver(
    resolve: (String?, String?, String?) -> GpgAgentMetadataResolution?,
) = object : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentMetadataResolution? = resolve(privateKeyArmored, publicKeyArmored, fingerprint)
}

private fun metadataResolution(
    metadata: GpgAgentKeyMetadata = TEST_METADATA,
) = GpgAgentMetadataResolution(
    metadata = metadata,
    authorization = GpgAgentAuthorizationSnapshot(
        evaluatedAtEpochSeconds = 0L,
        policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
        keys = emptyList(),
    ),
)

private fun publicKeyParser(
    keyInfo: GpgPublicKeyInfo?,
) = object : GpgPublicKeyParser {
    override fun parse(armored: String): GpgPublicKeyParseResult = GpgPublicKeyParseResult.Success(
        keys = listOfNotNull(keyInfo),
    )
}

private fun publicKeyInfo(
    userIds: List<String> = listOf("User <user@example.com>"),
    algorithm: String = "RSA",
) = GpgPublicKeyInfo(
    fingerprint = TEST_FINGERPRINT,
    keyId = "12345678",
    algorithm = algorithm,
    bitStrength = null,
    userIds = userIds,
    emails = emptyList(),
    createdAt = null,
    expiresAt = null,
    revoked = false,
    canSign = true,
    canEncrypt = true,
    publicKeyArmored = "LOCAL PUBLIC",
    subKeys = emptyList(),
)
