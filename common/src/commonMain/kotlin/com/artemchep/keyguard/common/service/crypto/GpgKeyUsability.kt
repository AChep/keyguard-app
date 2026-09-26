package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import kotlin.time.Instant

fun GpgPublicKeyInfo.isExpiredAt(now: Instant): Boolean =
    expiresAt?.let { it <= now } == true

/**
 * True when the key's metadata (expiration, capabilities) was read off a
 * verified self-signature. A weak-hash template that a renewal repairs still
 * qualifies; a key rejected before authentication does not, so its
 * `expiresAt == null` means unknown rather than unlimited.
 */
val GpgPublicKeyInfo.hasAuthenticatedMetadata: Boolean
    get() = authenticated || renewal == GpgRenewalAuthorization.TEMPLATE_ONLY

fun GpgPublicSubKeyInfo.isExpiredAt(now: Instant): Boolean =
    expiresAt?.let { it <= now } == true

/** True while the primary key is neither revoked nor expired. */
fun GpgPublicKeyInfo.isActiveAt(now: Instant): Boolean =
    !revoked && !isExpiredAt(now)

/** True while the subkey is neither revoked nor expired. */
fun GpgPublicSubKeyInfo.isActiveAt(now: Instant): Boolean =
    !revoked && !isExpiredAt(now)

/**
 * True when this certificate can still produce a signature, either with the
 * primary key itself or through a subkey that is still active.
 *
 * The primary must be active either way: a revoked or expired primary
 * invalidates the whole certificate, including its subkeys.
 */
fun GpgPublicKeyInfo.canSignAt(now: Instant): Boolean =
    isActiveAt(now) &&
            (canSign || subKeys.any { it.canSign && it.isActiveAt(now) })

/**
 * True when this certificate can still be used as an encryption recipient,
 * either with the primary key itself or through a subkey that is still
 * active. Decryption additionally requires the private material.
 */
fun GpgPublicKeyInfo.canEncryptAt(now: Instant): Boolean =
    isActiveAt(now) &&
            (canEncrypt || subKeys.any { it.canEncrypt && it.isActiveAt(now) })
