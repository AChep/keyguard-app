package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialContributions
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialInputContribution
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialInputError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialOperationalError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialPairError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileFailure
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.test.gpgCanonicalMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Suppress("FunctionNaming")
class CipherConflictResolverGpgTest {
    @Test
    fun `same primary key reconciles local and remote certificate material`() {
        val baseKey = gpgKey(publicMaterial = "base-public")
        val localKey = gpgKey(publicMaterial = "local-public", privateMaterial = "local-secret")
        val remoteKey = gpgKey(publicMaterial = "remote-public")
        val reconciler = RecordingReconciler(
            result = success(publicMaterial = "merged-public", privateMaterial = "merged-secret"),
        )

        val resolution = resolve(
            base = cipher(baseKey),
            local = cipher(localKey),
            remote = cipher(remoteKey),
            reconciler = reconciler,
            metadataResolver = rebuildingMetadataResolver,
        )

        assertEquals(
            ReconcileRequest(
                fingerprint = FINGERPRINT,
                existingPublic = "local-public",
                existingSecret = "local-secret",
                incomingPublic = "remote-public",
                incomingSecret = null,
            ),
            reconciler.request,
        )
        assertEquals("merged-public", resolution.cipher.gpgKey?.publicKeyArmored)
        assertEquals("merged-secret", resolution.cipher.gpgKey?.privateKeyArmored)
        assertEquals(FINGERPRINT, resolution.cipher.gpgKey?.fingerprint)
        assertEquals(REBUILT_METADATA, resolution.cipher.gpgKey?.metadata)
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `same primary key reconciles without a merge base and requests a write`() {
        val resolution = resolve(
            base = null,
            local = cipher(gpgKey(publicMaterial = "local-public", privateMaterial = "local-secret")),
            remote = cipher(gpgKey(publicMaterial = "remote-public")),
            reconciler = RecordingReconciler(
                result = success(publicMaterial = "merged-public", privateMaterial = "local-secret"),
            ),
        )

        assertEquals(CipherConflictResolution.Mode.RemoteFallback, resolution.mode)
        assertEquals("merged-public", resolution.cipher.gpgKey?.publicKeyArmored)
        assertEquals("local-secret", resolution.cipher.gpgKey?.privateKeyArmored)
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `replacement primary key does not inherit secret deletion ancestry from base`() {
        val localKey = gpgKey(
            publicMaterial = "replacement-local-public",
            privateMaterial = "replacement-local-secret",
        )
        val remoteKey = gpgKey(publicMaterial = "replacement-remote-public")
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "replacement-merged-public",
                privateMaterial = "replacement-local-secret",
            ),
        )

        val resolution = resolve(
            base = cipher(
                gpgKey(
                    publicMaterial = "base-public",
                    privateMaterial = "base-secret",
                    fingerprint = OTHER_FINGERPRINT,
                ),
            ),
            local = cipher(localKey),
            remote = cipher(remoteKey),
            reconciler = reconciler,
        )

        assertEquals(
            ReconcileRequest(
                fingerprint = FINGERPRINT,
                existingPublic = "replacement-local-public",
                existingSecret = "replacement-local-secret",
                incomingPublic = "replacement-remote-public",
                incomingSecret = null,
            ),
            reconciler.request,
        )
        assertEquals(
            "replacement-local-secret",
            resolution.cipher.gpgKey?.privateKeyArmored,
        )
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `noncanonical remote fingerprint requests a no-base write`() {
        val localKey = gpgKey(
            publicMaterial = "same-public",
            privateMaterial = "same-secret",
        )
        val remoteKey = localKey.copy(fingerprint = FORMATTED_FINGERPRINT)
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "same-public",
                privateMaterial = "same-secret",
            ),
        )

        val resolution = resolve(
            base = null,
            local = cipher(localKey),
            remote = cipher(remoteKey),
            reconciler = reconciler,
        )

        assertEquals(
            ReconcileRequest(
                fingerprint = FINGERPRINT,
                existingPublic = "same-public",
                existingSecret = "same-secret",
                incomingPublic = "same-public",
                incomingSecret = "same-secret",
            ),
            reconciler.request,
        )
        assertEquals(CipherConflictResolution.Mode.RemoteFallback, resolution.mode)
        assertEquals(FINGERPRINT, resolution.cipher.gpgKey?.fingerprint)
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `canonical remote fingerprint avoids a no-base write`() {
        val remoteKey = gpgKey(
            publicMaterial = "same-public",
            privateMaterial = "same-secret",
        )
        val localKey = remoteKey.copy(fingerprint = FORMATTED_FINGERPRINT)
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "same-public",
                privateMaterial = "same-secret",
            ),
        )

        val resolution = resolve(
            base = null,
            local = cipher(localKey),
            remote = cipher(remoteKey),
            reconciler = reconciler,
        )

        assertEquals(FINGERPRINT, reconciler.request?.fingerprint)
        assertEquals(FINGERPRINT, resolution.cipher.gpgKey?.fingerprint)
        assertFalse(resolution.requiresRemoteWrite)
    }

    @Test
    fun `three-way merge canonicalizes a remote fingerprint formatting change`() {
        val key = gpgKey(
            publicMaterial = "same-public",
            privateMaterial = "same-secret",
        )
        val base = cipher(key)
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "same-public",
                privateMaterial = "same-secret",
            ),
        )

        val resolution = resolve(
            base = base,
            local = base.copy(notes = "local notes"),
            remote = base.copy(
                gpgKey = key.copy(fingerprint = FORMATTED_FINGERPRINT),
            ),
            reconciler = reconciler,
        )

        assertEquals(FINGERPRINT, reconciler.request?.fingerprint)
        assertEquals(CipherConflictResolution.Mode.ThreeWay, resolution.mode)
        assertEquals(FINGERPRINT, resolution.cipher.gpgKey?.fingerprint)
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `successful reconciliation clears stale metadata when resolution is unavailable`() {
        val staleMetadata = gpgCanonicalMetadata(FINGERPRINT, "stale-keygrip")
        val resolution = resolve(
            base = cipher(gpgKey(publicMaterial = "base-public")),
            local = cipher(gpgKey(publicMaterial = "local-public", metadata = staleMetadata)),
            remote = cipher(gpgKey(publicMaterial = "remote-public", metadata = staleMetadata)),
            reconciler = RecordingReconciler(
                result = success(publicMaterial = "merged-public", privateMaterial = null),
            ),
        )

        assertNull(resolution.cipher.gpgKey?.metadata)
    }

    @Test
    fun `typed reconciliation failures keep the normal whole certificate winner`() {
        val remoteKey = gpgKey(publicMaterial = "remote-public", privateMaterial = "remote-secret")
        val failures = listOf(
            GpgCertificateMaterialReconcileFailure.InvalidInputs(
                existingPublic = GpgCertificateMaterialInputError.MalformedCertificate,
                incomingPublic = null,
                existingSecret = null,
                incomingSecret = null,
            ),
            GpgCertificateMaterialReconcileFailure.Pair(
                GpgCertificateMaterialPairError.ComponentCollision,
            ),
            GpgCertificateMaterialReconcileFailure.Pair(
                GpgCertificateMaterialPairError.ConflictingSecretMaterial,
            ),
            GpgCertificateMaterialReconcileFailure.Operational(
                GpgCertificateMaterialOperationalError.ResourceLimit,
            ),
        )

        failures.forEach { failure ->
            val resolution = resolve(
                base = cipher(gpgKey(publicMaterial = "base-public")),
                local = cipher(gpgKey(publicMaterial = "local-public", privateMaterial = "local-secret")),
                remote = cipher(remoteKey),
                reconciler = RecordingReconciler(
                    result = GpgCertificateMaterialReconcileResult.Error(failure),
                ),
            )

            assertEquals(remoteKey, resolution.cipher.gpgKey, failure.toString())
        }
    }

    @Test
    fun `typed reconciliation failure preserves a sole local or remote replacement`() {
        val baseKey = gpgKey(publicMaterial = "base-public")
        val localKey = gpgKey(publicMaterial = "local-public", privateMaterial = "local-secret")
        val remoteKey = gpgKey(publicMaterial = "remote-public", privateMaterial = "remote-secret")
        val failure = GpgCertificateMaterialReconcileResult.Error(
            GpgCertificateMaterialReconcileFailure.InvalidInputs(
                existingPublic = GpgCertificateMaterialInputError.MalformedCertificate,
                incomingPublic = null,
                existingSecret = null,
                incomingSecret = null,
            ),
        )

        val localReplacement = resolve(
            base = cipher(baseKey),
            local = cipher(localKey),
            remote = cipher(baseKey),
            reconciler = RecordingReconciler(failure),
        )
        val remoteReplacement = resolve(
            base = cipher(baseKey),
            local = cipher(baseKey),
            remote = cipher(remoteKey),
            reconciler = RecordingReconciler(failure),
        )

        assertEquals(localKey, localReplacement.cipher.gpgKey)
        assertEquals(remoteKey, remoteReplacement.cipher.gpgKey)
    }

    @Test
    fun `different primary keys keep the normal winner without reconciliation`() {
        val remoteKey = gpgKey(
            publicMaterial = "remote-public",
            fingerprint = OTHER_FINGERPRINT,
        )

        val resolution = resolve(
            base = cipher(gpgKey(publicMaterial = "base-public")),
            local = cipher(gpgKey(publicMaterial = "local-public")),
            remote = cipher(remoteKey),
            reconciler = UnexpectedGpgReconciler,
        )

        assertEquals(remoteKey, resolution.cipher.gpgKey)
    }

    @Test
    fun `local certificate deletion is not resurrected`() {
        val base = cipher(gpgKey(publicMaterial = "base-public"))
        val resolution = resolve(
            base = base,
            local = base.copy(gpgKey = null),
            remote = base.copy(notes = "remote notes"),
            reconciler = UnexpectedGpgReconciler,
        )

        assertNull(resolution.cipher.gpgKey)
    }

    @Test
    fun `remote certificate deletion is not resurrected`() {
        val base = cipher(gpgKey(publicMaterial = "base-public"))
        val resolution = resolve(
            base = base,
            local = base.copy(notes = "local notes"),
            remote = base.copy(gpgKey = null),
            reconciler = UnexpectedGpgReconciler,
        )

        assertNull(resolution.cipher.gpgKey)
    }

    @Test
    fun `selected local private material deletion is not resurrected`() {
        val baseKey = gpgKey(
            publicMaterial = "base-public",
            privateMaterial = "base-secret",
        )
        val base = cipher(baseKey)
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "merged-public",
                privateMaterial = "unexpected-secret",
            ),
        )

        val resolution = resolve(
            base = base,
            local = base.copy(
                gpgKey = baseKey.copy(privateKeyArmored = null),
            ),
            remote = base.copy(notes = "remote notes"),
            reconciler = reconciler,
        )

        assertEquals(
            ReconcileRequest(
                fingerprint = FINGERPRINT,
                existingPublic = "base-public",
                existingSecret = null,
                incomingPublic = "base-public",
                incomingSecret = null,
            ),
            reconciler.request,
        )
        assertEquals("merged-public", resolution.cipher.gpgKey?.publicKeyArmored)
        assertNull(resolution.cipher.gpgKey?.privateKeyArmored)
    }

    @Test
    fun `selected remote blank private material deletion is not resurrected`() {
        val baseKey = gpgKey(
            publicMaterial = "base-public",
            privateMaterial = "base-secret",
        )
        val base = cipher(baseKey)
        val reconciler = RecordingReconciler(
            result = success(
                publicMaterial = "merged-public",
                privateMaterial = "unexpected-secret",
            ),
        )

        val resolution = resolve(
            base = base,
            local = base.copy(notes = "local notes"),
            remote = base.copy(
                gpgKey = baseKey.copy(privateKeyArmored = ""),
            ),
            reconciler = reconciler,
        )

        assertEquals(
            ReconcileRequest(
                fingerprint = FINGERPRINT,
                existingPublic = "base-public",
                existingSecret = null,
                incomingPublic = "base-public",
                incomingSecret = null,
            ),
            reconciler.request,
        )
        assertEquals("merged-public", resolution.cipher.gpgKey?.publicKeyArmored)
        assertNull(resolution.cipher.gpgKey?.privateKeyArmored)
    }

    @Test
    fun `one-sided certificate additions are preserved without reconciliation`() {
        val key = gpgKey(publicMaterial = "added-public")
        val empty = cipher(gpgKey = null)

        val localAddition = resolve(
            base = empty,
            local = empty.copy(gpgKey = key),
            remote = empty,
            reconciler = UnexpectedGpgReconciler,
        )
        val remoteAddition = resolve(
            base = empty,
            local = empty,
            remote = empty.copy(gpgKey = key),
            reconciler = UnexpectedGpgReconciler,
        )

        assertEquals(key, localAddition.cipher.gpgKey)
        assertEquals(key, remoteAddition.cipher.gpgKey)
    }

    @Test
    fun `matching certificate material does not require a no-base write`() {
        val key = gpgKey(publicMaterial = "same-public", privateMaterial = "same-secret")
        val resolution = resolve(
            base = null,
            local = cipher(key),
            remote = cipher(key),
            reconciler = UnexpectedGpgReconciler,
        )

        assertFalse(resolution.requiresRemoteWrite)
        assertEquals(key, resolution.cipher.gpgKey)
    }

    private fun resolve(
        base: BitwardenCipher?,
        local: BitwardenCipher,
        remote: BitwardenCipher,
        reconciler: GpgCertificateMaterialReconciler,
        metadataResolver: GpgKeyMetadataResolver? = null,
    ) = resolveCipherConflict(
        base = base,
        local = local,
        remote = remote,
        at = MERGE_REVISION,
        preserveDisplacedSecretsInPasswordHistory = false,
        gpgCertificateMaterialReconciler = reconciler,
        gpgKeyMetadataResolver = metadataResolver,
    )

    private fun cipher(gpgKey: BitwardenCipher.GpgKey?) = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = BASE_REVISION,
        service = BitwardenService(version = BitwardenService.VERSION),
        name = "GPG key",
        notes = null,
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.GpgKey,
        secureNote = BitwardenCipher.SecureNote(),
        gpgKey = gpgKey,
    )

    private fun gpgKey(
        publicMaterial: String?,
        privateMaterial: String? = null,
        fingerprint: String = FINGERPRINT,
        metadata: com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata? = null,
    ) = BitwardenCipher.GpgKey(
        privateKeyArmored = privateMaterial,
        publicKeyArmored = publicMaterial,
        fingerprint = fingerprint,
        metadata = metadata,
    )

    private fun success(
        publicMaterial: String,
        privateMaterial: String?,
    ) = GpgCertificateMaterialReconcileResult.Success(
        localPublicMaterial = publicMaterial,
        localSecretMaterial = privateMaterial,
        transferablePublicCertificate = publicMaterial,
        transferableSecretKey = privateMaterial,
        primaryFingerprint = FINGERPRINT,
        contributions = EMPTY_CONTRIBUTIONS,
        withheldReasons = emptySet(),
    )

    private data class ReconcileRequest(
        val fingerprint: String,
        val existingPublic: String?,
        val existingSecret: String?,
        val incomingPublic: String?,
        val incomingSecret: String?,
    )

    private class RecordingReconciler(
        private val result: GpgCertificateMaterialReconcileResult,
    ) : GpgCertificateMaterialReconciler {
        var request: ReconcileRequest? = null
            private set

        override fun reconcile(
            expectedPrimaryFingerprint: String,
            existingPublicCertificate: String?,
            existingSecretCertificate: String?,
            incomingPublicCertificate: String?,
            incomingSecretCertificate: String?,
        ): GpgCertificateMaterialReconcileResult {
            request = ReconcileRequest(
                fingerprint = expectedPrimaryFingerprint,
                existingPublic = existingPublicCertificate,
                existingSecret = existingSecretCertificate,
                incomingPublic = incomingPublicCertificate,
                incomingSecret = incomingSecretCertificate,
            )
            return result
        }
    }

    private companion object {
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_FINGERPRINT = "89ABCDEF0123456789ABCDEF0123456789ABCDEF"
        val FORMATTED_FINGERPRINT = FINGERPRINT.lowercase().chunked(4).joinToString(" ")
        val BASE_REVISION = Instant.parse("2024-01-01T00:00:00Z")
        val MERGE_REVISION = Instant.parse("2024-01-02T00:00:00Z")
        val REBUILT_METADATA = gpgCanonicalMetadata(FINGERPRINT, "rebuilt-keygrip")
        val EMPTY_CONTRIBUTION = GpgCertificateMaterialInputContribution(
            present = false,
            uniquePublicEvidence = false,
            uniqueSecretCapability = false,
        )
        val EMPTY_CONTRIBUTIONS = GpgCertificateMaterialContributions(
            existingPublic = EMPTY_CONTRIBUTION,
            incomingPublic = EMPTY_CONTRIBUTION,
            existingSecret = EMPTY_CONTRIBUTION,
            incomingSecret = EMPTY_CONTRIBUTION,
        )
        val rebuildingMetadataResolver = object : GpgKeyMetadataResolver {
            override fun resolve(
                privateKeyArmored: String?,
                publicKeyArmored: String?,
                fingerprint: String?,
                candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
            ) = GpgAgentMetadataResolution(
                metadata = REBUILT_METADATA,
                authorization = GpgAgentAuthorizationSnapshot(
                    evaluatedAtEpochSeconds = 0L,
                    policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
                    keys = emptyList(),
                ),
            )
        }
    }
}

internal object UnexpectedGpgReconciler : GpgCertificateMaterialReconciler {
    override fun reconcile(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: String?,
        existingSecretCertificate: String?,
        incomingPublicCertificate: String?,
        incomingSecretCertificate: String?,
    ) = error("GPG reconciliation should not run")
}
