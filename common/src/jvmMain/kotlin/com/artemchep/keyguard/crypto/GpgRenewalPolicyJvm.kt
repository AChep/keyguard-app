package com.artemchep.keyguard.crypto

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import java.util.Date
import kotlin.time.Instant

/** GnuPG-compatible policy decisions kept separate from OpenPGP packet rewriting. */
internal class GpgRenewalPolicyJvm(
    private val now: () -> Instant,
    private val waitForClock: (milliseconds: Long) -> Unit,
) {
    fun replacementHashAlgorithm(
        signingAlgorithm: Int,
        templateHashAlgorithm: Int,
    ): Int {
        val preservesOriginalDigest = when (signingAlgorithm) {
            PublicKeyAlgorithmTags.DSA,
            PublicKeyAlgorithmTags.ECDSA,
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
                -> true

            else -> false
        }
        return when {
            preservesOriginalDigest -> templateHashAlgorithm
            templateHashAlgorithm == HashAlgorithmTags.SHA1 -> HashAlgorithmTags.SHA256
            templateHashAlgorithm == HashAlgorithmTags.RIPEMD160 -> HashAlgorithmTags.SHA256
            else -> templateHashAlgorithm
        }
    }

    /** Returns null when GnuPG's five-tick wait window is exhausted. */
    fun replacementSignatureCreationTime(
        templateCreationTime: Date,
    ): Date? {
        val templateEpochSeconds = templateCreationTime.time / 1_000L
        repeat(MAX_TIME_CONFLICT_WAITS + 1) { attempt ->
            val candidateEpochSeconds = now().epochSeconds
            if (candidateEpochSeconds > templateEpochSeconds) {
                return Date(candidateEpochSeconds * 1_000L)
            }
            if (attempt == MAX_TIME_CONFLICT_WAITS) {
                return null
            }
            try {
                waitForClock(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    /**
     * Mirrors GnuPG's unsigned-32-bit rebuild of Signature Expiration Time.
     * Null means the copied packet must be left untouched (absent, zero, or an
     * old timestamp/duration pair whose unsigned sum wrapped exactly to zero).
     */
    fun replacementSignatureExpirationDuration(
        templateCreationTime: Date,
        templateDurationSeconds: Long,
        replacementCreationTime: Date,
    ): Long? {
        val duration = templateDurationSeconds and UINT32_MASK
        if (duration == 0L) {
            return null
        }
        val templateTimestamp = templateCreationTime.time / 1_000L and UINT32_MASK
        val expirationTimestamp = (templateTimestamp + duration) and UINT32_MASK
        if (expirationTimestamp == 0L) {
            return null
        }
        val replacementTimestamp = replacementCreationTime.time / 1_000L and UINT32_MASK
        return if (expirationTimestamp > replacementTimestamp) {
            expirationTimestamp - replacementTimestamp
        } else {
            1L
        }
    }

    private companion object {
        const val MAX_TIME_CONFLICT_WAITS = 5
        const val UINT32_MASK = 0xFFFF_FFFFL
    }
}
