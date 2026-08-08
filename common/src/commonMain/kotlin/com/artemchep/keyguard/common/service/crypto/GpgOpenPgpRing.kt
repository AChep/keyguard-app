package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.MasterSession
import kotlin.time.Instant

internal data class GpgOpenPgpVault(
    val session: MasterSession.Key?,
    val rings: List<GpgOpenPgpRing>,
)

internal data class GpgOpenPgpRing(
    val accountId: String,
    val cipherId: String,
    val name: String,
    val info: GpgPublicKeyInfo,
    val hasSigningPrivateMaterial: Boolean,
    val hasDecryptionPrivateMaterial: Boolean,
    val privateKeyArmored: String?,
    /**
     * Evaluation instant for every usability property below, captured
     * once per request so a ring cannot report a key as usable in one
     * property and expired in the next.
     */
    val now: Instant,
) {
    val primaryKeyId: Long
        get() = fingerprintToKeyId(info.fingerprint)

    // Rings live for the duration of a single request, so caching
    // the derived key IDs keeps the O(N·K) lookups over them cheap.
    val allKeyIds: Set<Long> by lazy {
        buildSet {
            add(primaryKeyId)
            info.subKeys.forEach { add(fingerprintToKeyId(it.fingerprint)) }
        }
    }

    val canSign: Boolean
        get() = hasSigningPrivateMaterial && info.canSignAt(now)

    val canEncrypt: Boolean
        get() = info.canEncryptAt(now)

    val canDecrypt: Boolean
        get() = hasDecryptionPrivateMaterial && canEncrypt

    val canExport: Boolean
        get() = info.isActiveAt(now)

    val isExpired: Boolean
        get() = info.isExpiredAt(now)

    fun privateKey(): GpgOpenPgpPrivateKey? = privateKeyArmored
        ?.takeIf { it.isNotBlank() }
        ?.let {
            GpgOpenPgpPrivateKey(
                armored = it,
                preferredFingerprint = info.fingerprint,
            )
        }

    fun publicKey(): GpgOpenPgpPublicKey = GpgOpenPgpPublicKey(
        armored = info.publicKeyArmored,
    )
}
