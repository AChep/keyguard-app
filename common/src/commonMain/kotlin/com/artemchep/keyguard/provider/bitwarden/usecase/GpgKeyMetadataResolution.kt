package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgagent.isCanonical
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

internal fun BitwardenCipher.GpgKey.resolveGpgMetadata(
    old: BitwardenCipher.GpgKey?,
    resolver: GpgKeyMetadataResolver?,
): BitwardenCipher.GpgKey {
    val reusableInventory = old
        ?.metadata
        ?.takeIf { it.isCanonical && old.hasSameGpgMaterial(this) }
    val resolvedMetadata = resolver?.resolve(
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
    )?.metadata
        ?: metadata?.takeIf { it.isCanonical }
        ?: reusableInventory
    return copy(
        metadata = resolvedMetadata,
    )
}

private fun BitwardenCipher.GpgKey.hasSameGpgMaterial(
    other: BitwardenCipher.GpgKey,
): Boolean =
    privateKeyArmored == other.privateKeyArmored &&
            publicKeyArmored == other.publicKeyArmored &&
            fingerprint.normalizeGpgFingerprintOrNull() == other.fingerprint.normalizeGpgFingerprintOrNull()

private fun String?.normalizeGpgFingerprintOrNull(): String? =
    this
        ?.normalizeGpgFingerprint()
        ?.takeIf { it.isNotEmpty() }
