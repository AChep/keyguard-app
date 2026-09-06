package com.artemchep.keyguard.common.service.crypto

import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

class GpgOpenPgpRingOperationsTest {
    @Test
    fun `duplicate primary fingerprint revisions are merged before confirmation`() {
        val result = confirm(
            signerFingerprint = PRIMARY_FINGERPRINT,
            rings = listOf(
                ring("revision-b"),
                ring("revision-a"),
            ),
            evaluations = mapOf(
                "revision-a" to listOf(USER_ID),
                "revision-b" to listOf(USER_ID),
                "revision-a|revision-b" to listOf(USER_ID),
            ),
        )

        assertEquals(listOf(USER_ID), result.verification.confirmedUserIds)
        assertEquals(
            listOf("revision-a", "revision-a|revision-b", "revision-b"),
            result.evaluatedCertificates,
        )
        assertEquals(listOf("revision-a" to "revision-b"), result.merges)
    }

    @Test
    fun `duplicate subkey fingerprint revisions are merged before confirmation`() {
        val result = confirm(
            signerFingerprint = SUBKEY_FINGERPRINT,
            rings = listOf(
                ring("revision-a", subkeyFingerprint = SUBKEY_FINGERPRINT),
                ring("revision-b", subkeyFingerprint = SUBKEY_FINGERPRINT),
            ),
            evaluations = mapOf(
                "revision-a" to listOf(USER_ID),
                "revision-b" to listOf(USER_ID),
                "revision-a|revision-b" to listOf(USER_ID),
            ),
        )

        assertEquals(listOf(USER_ID), result.verification.confirmedUserIds)
        assertEquals(
            listOf("revision-a", "revision-a|revision-b", "revision-b"),
            result.evaluatedCertificates,
        )
    }

    @Test
    fun `duplicate revision confirmation is independent of ring order`() {
        val rings = listOf(
            ring("revision-a"),
            ring("revision-b"),
        )
        val evaluations = mapOf(
            "revision-a" to listOf(USER_ID),
            "revision-b" to listOf(USER_ID),
            "revision-a|revision-b" to listOf(USER_ID),
        )

        val forward = confirm(PRIMARY_FINGERPRINT, rings, evaluations)
        val reversed = confirm(PRIMARY_FINGERPRINT, rings.reversed(), evaluations)

        assertEquals(forward.verification.confirmedUserIds, reversed.verification.confirmedUserIds)
        assertEquals(forward.evaluatedCertificates, reversed.evaluatedCertificates)
        assertEquals(listOf(USER_ID), reversed.verification.confirmedUserIds)
    }

    @Test
    fun `merged revocation-like disagreement cannot retain a stale confirmation`() {
        val evaluations = mapOf(
            "certified-revision" to listOf(USER_ID),
            "revoked-revision" to emptyList(),
            // Packet merging is additive, so an omitted certification can
            // remain valid in the merged view. Revision agreement still
            // fails closed for the repository-level disagreement.
            "certified-revision|revoked-revision" to listOf(USER_ID),
        )

        listOf(
            listOf(ring("certified-revision"), ring("revoked-revision")),
            listOf(ring("revoked-revision"), ring("certified-revision")),
        ).forEach { rings ->
            val result = confirm(PRIMARY_FINGERPRINT, rings, evaluations)

            assertEquals(emptyList(), result.verification.confirmedUserIds)
            assertEquals(
                listOf(
                    "certified-revision",
                    "certified-revision|revoked-revision",
                    "revoked-revision",
                ),
                result.evaluatedCertificates,
            )
        }
    }

    @Test
    fun `inline read limits operation keys while confirmation uses all vault evidence`() {
        val certified = ring("certified-revision", canDecrypt = true)
        val revoked = ring("revoked-revision", canDecrypt = true)
        val result = confirm(
            signerFingerprint = PRIMARY_FINGERPRINT,
            rings = listOf(certified, revoked),
            operationRings = listOf(certified),
            evaluations = revocationDisagreementEvaluations(),
        )

        assertEquals(emptyList(), result.verification.confirmedUserIds)
        assertEquals(listOf("certified-revision"), result.operationPublicKeys)
        assertEquals(listOf("private-certified-revision"), result.operationPrivateKeys)
        assertEquals(
            listOf(
                "certified-revision",
                "certified-revision|revoked-revision",
                "revoked-revision",
            ),
            result.evaluatedCertificates,
        )
    }

    @Test
    fun `detached verification limits operation keys while confirmation uses all vault evidence`() {
        val certified = ring("certified-revision")
        val revoked = ring("revoked-revision")
        val result = confirm(
            signerFingerprint = PRIMARY_FINGERPRINT,
            rings = listOf(certified, revoked),
            operationRings = listOf(certified),
            evaluations = revocationDisagreementEvaluations(),
            detached = true,
        )

        assertEquals(emptyList(), result.verification.confirmedUserIds)
        assertEquals(listOf("certified-revision"), result.operationPublicKeys)
        assertEquals(emptyList(), result.operationPrivateKeys)
        assertEquals(
            listOf(
                "certified-revision",
                "certified-revision|revoked-revision",
                "revoked-revision",
            ),
            result.evaluatedCertificates,
        )
    }

    @Test
    fun `policy conflict strips confirmed user ids from a certified signer`() {
        val result = confirm(
            signerFingerprint = PRIMARY_FINGERPRINT,
            rings = listOf(ring("revision-a")),
            evaluations = mapOf("revision-a" to listOf(USER_ID)),
            warnings = listOf(GpgOpenPgpVerificationWarning.POLICY_CONFLICT),
        )

        assertEquals(emptyList(), result.verification.confirmedUserIds)
        assertEquals(emptyList(), result.evaluatedCertificates)
    }

    @Test
    fun `read key scope rejects a selection outside its vault snapshot`() {
        val vault = GpgOpenPgpVault(
            session = null,
            rings = listOf(ring("stored-revision")),
            certificationAuthorities = listOf(AUTHORITY),
        )

        assertFailsWith<IllegalArgumentException> {
            vault.readKeyScope(listOf(ring("outside-revision")))
        }
    }

    private fun confirm(
        signerFingerprint: String,
        rings: List<GpgOpenPgpRing>,
        evaluations: Map<String, List<String>>,
        operationRings: List<GpgOpenPgpRing> = rings,
        detached: Boolean = false,
        warnings: List<GpgOpenPgpVerificationWarning> = emptyList(),
    ): ConfirmationResult {
        val service = RecordingOpenPgpService(
            verification = verification(signerFingerprint, warnings),
            evaluations = evaluations,
        )
        val reconciler = RecordingCertificateMaterialReconciler()
        val operations = GpgOpenPgpRingOperations(
            service = service,
            certificateMaterialReconciler = reconciler,
        )

        val keys = GpgOpenPgpVault(
            session = null,
            rings = rings,
            certificationAuthorities = listOf(AUTHORITY),
        ).readKeyScope(operationRings)
        val verification = if (detached) {
            operations.verifyDetached(
                keys = keys,
                input = Buffer(),
                output = Buffer(),
                signature = byteArrayOf(1, 2, 3),
            ).verification
        } else {
            val result = assertIs<GpgOpenPgpReadFileResult.Message>(
                operations.read(
                    keys = keys,
                    input = Buffer(),
                    output = Buffer(),
                ),
            )
            assertNotNull(result.verification)
        }
        return ConfirmationResult(
            verification = verification,
            evaluatedCertificates = service.evaluatedCertificates,
            merges = reconciler.merges,
            operationPublicKeys = service.operationPublicKeys,
            operationPrivateKeys = service.operationPrivateKeys,
        )
    }

    private fun revocationDisagreementEvaluations() = mapOf(
        "certified-revision" to listOf(USER_ID),
        "revoked-revision" to emptyList(),
        "certified-revision|revoked-revision" to listOf(USER_ID),
    )

    private fun verification(
        fingerprint: String,
        warnings: List<GpgOpenPgpVerificationWarning> = emptyList(),
    ) = GpgOpenPgpVerification(
        status = GpgOpenPgpVerificationStatus.VALID,
        keyId = fingerprint.takeLast(16),
        fingerprint = fingerprint,
        userIds = listOf(USER_ID),
        createdAt = REFERENCE_TIME,
        warnings = warnings,
    )

    private fun ring(
        publicKey: String,
        subkeyFingerprint: String? = null,
        canDecrypt: Boolean = false,
    ) = GpgOpenPgpRing(
        accountId = "account",
        cipherId = publicKey,
        name = publicKey,
        info = GpgPublicKeyInfo(
            fingerprint = PRIMARY_FINGERPRINT,
            keyId = PRIMARY_FINGERPRINT.takeLast(16),
            algorithm = "EdDSA",
            bitStrength = 255,
            userIds = listOf(USER_ID),
            emails = listOf("alice@example.test"),
            createdAt = REFERENCE_TIME,
            expiresAt = null,
            revoked = false,
            canSign = true,
            canEncrypt = canDecrypt,
            publicKeyArmored = publicKey,
            subKeys = subkeyFingerprint?.let { fingerprint ->
                listOf(
                    GpgPublicSubKeyInfo(
                        fingerprint = fingerprint,
                        keyId = fingerprint.takeLast(16),
                        algorithm = "EdDSA",
                        canSign = true,
                        canEncrypt = false,
                        revoked = false,
                        expiresAt = null,
                    ),
                )
            }.orEmpty(),
        ),
        hasSigningPrivateMaterial = false,
        hasDecryptionPrivateMaterial = canDecrypt,
        privateKeyArmored = "private-$publicKey".takeIf { canDecrypt },
        now = REFERENCE_TIME,
    )

    private data class ConfirmationResult(
        val verification: GpgOpenPgpVerification,
        val evaluatedCertificates: List<String>,
        val merges: List<Pair<String, String>>,
        val operationPublicKeys: List<String>,
        val operationPrivateKeys: List<String>,
    )

    private companion object {
        const val PRIMARY_FINGERPRINT = "A0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3"
        const val SUBKEY_FINGERPRINT = "C0C1C2C3C4C5C6C7C8C9CACBCCCDCECFD0D1D2D3"
        const val USER_ID = "Alice <alice@example.test>"
        val REFERENCE_TIME = Instant.fromEpochSeconds(1_700_000_000)
        val AUTHORITY = GpgOpenPgpCertificationAuthority(
            publicKey = GpgOpenPgpPublicKey("authority"),
            primaryFingerprint = "E0E1E2E3E4E5E6E7E8E9EAEBECEDEEEFF0F1F2F3",
        )
    }
}

private class RecordingCertificateMaterialReconciler : GpgCertificateMaterialReconciler {
    val merges = mutableListOf<Pair<String, String>>()

    override fun reconcile(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: String?,
        existingSecretCertificate: String?,
        incomingPublicCertificate: String?,
        incomingSecretCertificate: String?,
    ): GpgCertificateMaterialReconcileResult {
        val existing = requireNotNull(existingPublicCertificate)
        val incoming = requireNotNull(incomingPublicCertificate)
        require(existingSecretCertificate == null)
        require(incomingSecretCertificate == null)
        merges += existing to incoming
        val merged = (existing.split('|') + incoming.split('|'))
            .distinct()
            .sorted()
            .joinToString("|")
        val present = GpgCertificateMaterialInputContribution(
            present = true,
            uniquePublicEvidence = true,
            uniqueSecretCapability = false,
        )
        val absent = GpgCertificateMaterialInputContribution(
            present = false,
            uniquePublicEvidence = false,
            uniqueSecretCapability = false,
        )
        return GpgCertificateMaterialReconcileResult.Success(
            localPublicMaterial = merged,
            localSecretMaterial = null,
            transferablePublicCertificate = merged,
            transferableSecretKey = null,
            primaryFingerprint = expectedPrimaryFingerprint,
            contributions = GpgCertificateMaterialContributions(
                existingPublic = present,
                incomingPublic = present,
                existingSecret = absent,
                incomingSecret = absent,
            ),
            withheldReasons = emptySet(),
        )
    }
}

private class RecordingOpenPgpService(
    private val verification: GpgOpenPgpVerification,
    private val evaluations: Map<String, List<String>>,
) : GpgOpenPgpService {
    val evaluatedCertificates = mutableListOf<String>()
    val operationPublicKeys = mutableListOf<String>()
    val operationPrivateKeys = mutableListOf<String>()

    override fun evaluateUserIdCertifications(
        request: GpgOpenPgpUserIdCertificationRequest,
    ): List<String> {
        evaluatedCertificates += request.publicKey.armored
        return evaluations[request.publicKey.armored].orEmpty()
    }

    override fun readFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult {
        operationPublicKeys += request.publicKeys.map(GpgOpenPgpPublicKey::armored)
        operationPrivateKeys += request.privateKeys.map(GpgOpenPgpPrivateKey::armored)
        return GpgOpenPgpReadFileResult.Message(
            verification = verification,
            encrypted = false,
        )
    }

    override fun verifyClearSignedText(request: GpgOpenPgpVerifyTextRequest) = unused()

    override fun verifyDetachedText(request: GpgOpenPgpVerifyDetachedTextRequest) = unused()

    override fun verifyFile(request: GpgOpenPgpVerifyFileRequest): GpgOpenPgpVerification {
        operationPublicKeys += request.publicKeys.map(GpgOpenPgpPublicKey::armored)
        return verification
    }

    override fun clearSignText(request: GpgOpenPgpSignTextRequest): String = unused()

    override fun signTextDetached(request: GpgOpenPgpSignTextRequest): String = unused()

    override fun encryptText(request: GpgOpenPgpEncryptTextRequest): String = unused()

    override fun decryptText(request: GpgOpenPgpDecryptTextRequest) = unused()

    override fun exportPublicKey(request: GpgOpenPgpExportPublicKeyRequest): Unit = unused()

    override fun signFile(request: GpgOpenPgpSignFileRequest): Unit = unused()

    override fun clearSignFile(request: GpgOpenPgpClearSignFileRequest): Unit = unused()

    override fun verifyClearSignedFile(request: GpgOpenPgpReadFileRequest) = unused()

    override fun encryptFile(request: GpgOpenPgpEncryptFileRequest): Unit = unused()

    override fun decryptFile(request: GpgOpenPgpReadFileRequest) = unused()

    private fun unused(): Nothing = error("Unexpected OpenPGP operation")
}
