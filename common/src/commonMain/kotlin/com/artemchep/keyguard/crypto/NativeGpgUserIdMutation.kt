package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMaterial
import kotlin.time.Instant

internal fun <NativeResult, DomainResult> mutateSignedUserId(
    key: GpgKeyMaterial,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    now: () -> Instant,
    waitForClock: (milliseconds: Long) -> Boolean,
    internalFailureError: () -> DomainResult,
    isTimeConflict: (NativeResult) -> Boolean,
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
                        isTimeConflict = isTimeConflict,
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

internal inline fun <T> retryNativeTimeConflicts(
    now: () -> Instant,
    waitForClock: (milliseconds: Long) -> Boolean,
    isTimeConflict: (T) -> Boolean,
    update: (referenceTimeEpochSeconds: Long) -> T,
): T {
    var waitsRemaining = MAX_TIME_CONFLICT_WAITS
    var result: T
    var shouldRetry: Boolean
    do {
        val reference = now()
        result = update(reference.epochSeconds)
        shouldRetry = isTimeConflict(result) &&
            waitsRemaining > 0 &&
            waitForClock(reference.millisecondsUntilNextEpochSecond())
        if (shouldRetry) waitsRemaining -= 1
    } while (shouldRetry)
    return result
}

private fun Instant.millisecondsUntilNextEpochSecond(): Long =
    MILLISECONDS_PER_SECOND - toEpochMilliseconds().mod(MILLISECONDS_PER_SECOND)

private const val MAX_TIME_CONFLICT_WAITS = 5
private const val MILLISECONDS_PER_SECOND = 1_000L
