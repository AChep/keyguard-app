package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.getMergeRules
import com.artemchep.keyguard.provider.bitwarden.usecase.normalizeGpgFingerprintOrNull
import com.artemchep.keyguard.provider.bitwarden.usecase.resolveGpgMetadata
import com.artemchep.keyguard.provider.bitwarden.usecase.util.with3WayMergePasswordHistoryOrNull
import kotlin.time.Instant

private val cipherMergeRules by lazy { BitwardenCipher.getMergeRules() }

internal data class CipherConflictResolution(
    val cipher: BitwardenCipher,
    val requiresRemoteWrite: Boolean,
    val mode: Mode,
) {
    enum class Mode {
        ThreeWay,
        RemoteFallback,
    }
}

/**
 * Resolves a cipher changed both locally and remotely.
 *
 * The field-level rules intentionally remain provider-independent so Bitwarden
 * and KeePass resolve the same conflict in the same way. Provider operations
 * are still responsible for choosing whether their remote format can durably
 * store displaced secrets, decoding the remote model, and publishing
 * [CipherConflictResolution.cipher].
 */
internal fun resolveCipherConflict(
    base: BitwardenCipher?,
    local: BitwardenCipher,
    remote: BitwardenCipher,
    at: Instant,
    preserveDisplacedSecretsInPasswordHistory: Boolean,
    gpgCertificateMaterialReconciler: GpgCertificateMaterialReconciler,
    gpgKeyMetadataResolver: GpgKeyMetadataResolver?,
): CipherConflictResolution {
    val merged = base?.let {
        with(ModelDiffUtil()) {
            cipherMergeRules.merge(it, local, remote)
        } as BitwardenCipher?
    }
    val resolution = if (merged != null) {
        // TODO: Password history merge re-introduces deleted password-history
        //  entries during conflict merge. A remote/user deletion can be undone
        //  and uploaded again if the local side still has that base entry.
        val withHistory = if (preserveDisplacedSecretsInPasswordHistory) {
            merged.with3WayMergePasswordHistoryOrNull(
                at = at,
                remote,
                local,
            ) ?: merged
        } else {
            merged
        }
        CipherConflictResolution(
            cipher = withHistory.copy(revisionDate = at),
            requiresRemoteWrite = true,
            mode = CipherConflictResolution.Mode.ThreeWay,
        )
    } else {
        val fallbackWithHistory = if (preserveDisplacedSecretsInPasswordHistory) {
            remote.with3WayMergePasswordHistoryOrNull(
                at = at,
                local,
            )
        } else {
            null
        }
        CipherConflictResolution(
            cipher = fallbackWithHistory?.copy(revisionDate = at) ?: remote,
            requiresRemoteWrite = fallbackWithHistory != null,
            mode = CipherConflictResolution.Mode.RemoteFallback,
        )
    }
    return resolution.reconcileGpgCertificateMaterial(
        base = base,
        local = local,
        remote = remote,
        reconciler = gpgCertificateMaterialReconciler,
        metadataResolver = gpgKeyMetadataResolver,
    )
}

private fun CipherConflictResolution.reconcileGpgCertificateMaterial(
    base: BitwardenCipher?,
    local: BitwardenCipher,
    remote: BitwardenCipher,
    reconciler: GpgCertificateMaterialReconciler,
    metadataResolver: GpgKeyMetadataResolver?,
): CipherConflictResolution {
    val localKey = local.gpgKey ?: return this
    val remoteKey = remote.gpgKey ?: return this
    val selectedKey = cipher.gpgKey ?: return this
    if (localKey.hasSamePersistedCertificateMaterial(remoteKey)) return this

    val localFingerprint = localKey.fingerprint.normalizeGpgFingerprintOrNull()
        ?: return this
    val remoteFingerprint = remoteKey.fingerprint.normalizeGpgFingerprintOrNull()
        ?: return this
    if (localFingerprint != remoteFingerprint) return this
    if (!localKey.hasCertificateMaterial() || !remoteKey.hasCertificateMaterial()) return this

    val (localSecretMaterial, remoteSecretMaterial) = resolveGpgSecretMaterialInputs(
        base = base?.gpgKey,
        expectedPrimaryFingerprint = localFingerprint,
        local = localKey,
        remote = remoteKey,
        selected = selectedKey,
    )
    val hasSecretMaterial = localSecretMaterial != null || remoteSecretMaterial != null
    val merged = reconciler.reconcile(
        expectedPrimaryFingerprint = localFingerprint,
        existingPublicCertificate = localKey.publicKeyArmored.nonBlankCertificateMaterialOrNull(),
        existingSecretCertificate = localSecretMaterial,
        incomingPublicCertificate = remoteKey.publicKeyArmored.nonBlankCertificateMaterialOrNull(),
        incomingSecretCertificate = remoteSecretMaterial,
    ) as? GpgCertificateMaterialReconcileResult.Success
        ?: return this
    val mergedFingerprint = merged.primaryFingerprint.normalizeGpgFingerprint()
    if (merged.localPublicMaterial.isBlank() || mergedFingerprint != localFingerprint) {
        return this
    }

    val reconciledKey = BitwardenCipher.GpgKey(
        privateKeyArmored = merged.localSecretMaterial.takeIf { hasSecretMaterial },
        publicKeyArmored = merged.localPublicMaterial,
        fingerprint = mergedFingerprint,
    ).resolveGpgMetadata(
        old = selectedKey,
        resolver = metadataResolver,
    )
    return copy(
        cipher = cipher.copy(gpgKey = reconciledKey),
        requiresRemoteWrite = requiresRemoteWrite ||
                !reconciledKey.hasSamePersistedCertificateMaterial(remoteKey),
    )
}

private fun BitwardenCipher.GpgKey.hasCertificateMaterial(): Boolean =
    !publicKeyArmored.isNullOrBlank() || !privateKeyArmored.isNullOrBlank()

private fun String?.nonBlankCertificateMaterialOrNull(): String? =
    this?.takeIf { it.isNotBlank() }

/** Prevents the additive reconciler from restoring secret material deleted from the same key. */
private fun resolveGpgSecretMaterialInputs(
    base: BitwardenCipher.GpgKey?,
    expectedPrimaryFingerprint: String,
    local: BitwardenCipher.GpgKey,
    remote: BitwardenCipher.GpgKey,
    selected: BitwardenCipher.GpgKey,
): Pair<String?, String?> {
    val localMaterial = local.privateKeyArmored.nonBlankCertificateMaterialOrNull()
    val remoteMaterial = remote.privateKeyArmored.nonBlankCertificateMaterialOrNull()
    val baseKey = base
        ?.takeIf {
            it.fingerprint.normalizeGpgFingerprintOrNull() == expectedPrimaryFingerprint
        }
        ?: return localMaterial to remoteMaterial
    val baseMaterial = baseKey.privateKeyArmored.nonBlankCertificateMaterialOrNull()
    if (baseMaterial == null) return localMaterial to remoteMaterial

    val localDeleted = localMaterial == null && remoteMaterial == baseMaterial
    val remoteDeleted = remoteMaterial == null && localMaterial == baseMaterial
    val selectedMaterial = selected.privateKeyArmored.nonBlankCertificateMaterialOrNull()
    val selectedDeletionConflict = selectedMaterial == null &&
            (localMaterial != baseMaterial || remoteMaterial != baseMaterial)
    return if (localDeleted || remoteDeleted || selectedDeletionConflict) {
        null to null
    } else {
        localMaterial to remoteMaterial
    }
}

/**
 * Compares fingerprints raw on purpose: a normalization-only difference
 * still has to be written back to the remote side.
 */
private fun BitwardenCipher.GpgKey.hasSamePersistedCertificateMaterial(
    other: BitwardenCipher.GpgKey,
): Boolean =
    privateKeyArmored == other.privateKeyArmored &&
            publicKeyArmored == other.publicKeyArmored &&
            fingerprint == other.fingerprint
