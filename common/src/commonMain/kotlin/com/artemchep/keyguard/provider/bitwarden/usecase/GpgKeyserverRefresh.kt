package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.resolveDownloadedGpgKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.resolveGpgKeyserverRefreshKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain

/** Rechecks eligibility against the row being updated, not the pre-download snapshot. */
internal fun BitwardenCipher.gpgKeyForRefresh(
    expectedAccountId: String,
    expectedFingerprint: String,
): BitwardenCipher.GpgKey? {
    if (
        accountId != expectedAccountId || deletedDate != null || service.deleted
    ) {
        return null
    }
    val resolved = resolveGpgKeyserverRefreshKey(
        key = gpgKey?.toDomain(),
        legacyField = { name -> fields.firstOrNull { it.name == name }?.value },
    ) ?: return null
    if (resolved.fingerprint != expectedFingerprint) {
        return null
    }
    // Preserve absent private material as stored, so an unchanged refresh does
    // not become a write solely because an empty string was normalized to null.
    return (gpgKey ?: BitwardenCipher.GpgKey()).copy(
        publicKeyArmored = resolved.publicKeyArmored,
        fingerprint = resolved.fingerprint,
    )
}

/**
 * Returns null on a failed refresh, leaving the original material and metadata untouched.
 * Local evidence is persisted even when it cannot be included in a transferable certificate.
 * Neither input is discarded to repair malformed material.
 */
internal fun BitwardenCipher.GpgKey.withGpgKeyserverRefresh(
    expectedPrimaryFingerprint: String,
    result: DGpgKeyserverResult,
    reconciler: GpgCertificateMaterialReconciler,
    resolver: GpgKeyMetadataResolver,
): BitwardenCipher.GpgKey? = runCatchingNonFatal {
    val fingerprint = expectedPrimaryFingerprint.normalizeGpgFingerprint()
    if (publicKeyArmored.isNullOrBlank() || !privateKeyArmored.isNullOrBlank()) {
        return@runCatchingNonFatal null
    }
    if (
        result.publicKeyArmored.isNullOrBlank() ||
        result.fingerprint.normalizeGpgFingerprint() != fingerprint
    ) {
        return@runCatchingNonFatal null
    }
    val merged = reconciler.reconcile(
        expectedPrimaryFingerprint = fingerprint,
        existingPublicCertificate = publicKeyArmored,
        existingSecretCertificate = null,
        incomingPublicCertificate = result.publicKeyArmored,
        incomingSecretCertificate = null,
    ) as? GpgCertificateMaterialReconcileResult.Success
        ?: return@runCatchingNonFatal null
    if (
        merged.primaryFingerprint != fingerprint || merged.localPublicMaterial.isBlank()
    ) {
        return@runCatchingNonFatal null
    }
    if (merged.localSecretMaterial != null || merged.transferableSecretKey != null) {
        return@runCatchingNonFatal null
    }
    val metadata = resolver.resolveDownloadedGpgKeyMetadata(
        publicKeyArmored = merged.localPublicMaterial,
        fingerprint = fingerprint,
    )
    copy(
        publicKeyArmored = merged.localPublicMaterial,
        fingerprint = fingerprint,
        metadata = metadata,
    )
}.getOrNull()
