package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMaterial
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal fun <NativeResult, DomainResult> mutateSignedUserId(
    key: GpgKeyMaterial,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    now: () -> Instant,
    waitForClock: (milliseconds: Long) -> Boolean,
    internalFailureError: () -> DomainResult,
    retryAt: (NativeResult) -> Long?,
    nativeMutation: (
        privateKey: ByteArray,
        publicKey: ByteArray,
        expectedPrimaryFingerprint: String,
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ) -> NativeResult,
    toDomain: (NativeResult) -> DomainResult,
): DomainResult {
    val privateKey = key.privateKeyArmored.encodeToByteArray()
    val publicKey = key.publicKeyArmored.encodeToByteArray()
    val fingerprint = key.fingerprint.normalizeGpgFingerprint()
    return try {
        runCatchingNonFatal {
            // Candidate preselection parses certificates natively, so it stays outside
            // the retry loop; only the final mutation is time-sensitive.
            candidateRevocationKeys
                .withEncodedRevocationKeyCandidates(
                    targetPrivateKeys = listOf(EncodedRevocationTarget(privateKey, fingerprint)),
                    targetPublicKeys = listOf(EncodedRevocationTarget(publicKey, fingerprint)),
                    referenceTimeEpochSeconds = now().epochSeconds,
                ) { candidateKeys, _ ->
                    retryNativeTimeConflicts(
                        now = now,
                        waitForClock = waitForClock,
                        retryAt = retryAt,
                    ) { referenceTimeEpochSeconds ->
                        nativeMutation(
                            privateKey,
                            publicKey,
                            fingerprint,
                            candidateKeys,
                            referenceTimeEpochSeconds,
                        )
                    }
                }.let(toDomain)
        }.getOrElse { internalFailureError() }
    } finally {
        privateKey.fill(0)
        publicKey.fill(0)
    }
}

internal inline fun <T> withDecodedKeyMaterial(
    material: NativeOpenPgpKeyMaterial,
    certificateIndex: NativeOpenPgpCertificateIndex,
    certificateArmored: ByteArray,
    block: (key: GpgKeyMaterial, certificateArmored: String) -> T,
): T =
    material.useAsGpgKeyMaterial(certificateIndex) { key ->
        try {
            block(key, certificateArmored.decodeToString(throwOnInvalidSequence = true))
        } finally {
            certificateArmored.fill(0)
        }
    }

/** Retries explicit clock conflicts, with a bounded total wait and fresh policy time. */
internal inline fun <T> retryNativeTimeConflicts(
    now: () -> Instant,
    waitForClock: (milliseconds: Long) -> Boolean,
    retryAt: (T) -> Long?,
    update: (referenceTimeEpochSeconds: Long) -> T,
): T {
    var waitBudget = MAX_CLOCK_WAIT_MILLISECONDS
    var retriesRemaining = MAX_CLOCK_RETRIES
    while (true) {
        val result = update(now().epochSeconds)
        val retryTime = retryAt(result)
        if (retryTime == null || retriesRemaining-- == 0) return result
        val remaining = Instant.fromEpochSeconds(retryTime) - now()
        val wholeMilliseconds = remaining.inWholeMilliseconds.coerceAtLeast(0)
        // Round up so a sub-millisecond remainder cannot exhaust retries before the next second.
        val delay = wholeMilliseconds + if (remaining > wholeMilliseconds.milliseconds) 1 else 0
        if (delay > waitBudget || (delay > 0 && !waitForClock(delay))) return result
        waitBudget -= delay
    }
}

private const val MAX_CLOCK_RETRIES = 5
private const val MAX_CLOCK_WAIT_MILLISECONDS = 5_000L
