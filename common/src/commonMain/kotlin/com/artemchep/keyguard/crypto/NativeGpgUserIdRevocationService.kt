package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import com.artemchep.keyguard.common.util.sleepBlocking
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdRevocationResult
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgUserIdRevocationService : GpgUserIdRevocationService {
    override val isSupported: Boolean
        get() = true

    override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
        revoke(
            request = request,
            now = { Clock.System.now() },
            waitForClock = ::sleepBlocking,
        )

    internal fun revoke(
        request: GpgUserIdRevocationRequest,
        now: () -> Instant,
        waitForClock: (milliseconds: Long) -> Boolean,
    ): GpgUserIdRevocationResult {
        if (request.key.privateKeyArmored.isBlank()) {
            return GpgUserIdRevocationResult.Error(GpgUserIdMutationError.EmptyPrivateKey)
        }
        return mutateSignedUserId(
            key = request.key,
            candidateRevocationKeys = request.candidateRevocationKeys,
            now = now,
            waitForClock = waitForClock,
            internalFailureError = {
                GpgUserIdRevocationResult.Error(GpgUserIdMutationError.InternalFailure)
            },
            retryAt = { result -> (result as? NativeOpenPgpUserIdRevocationResult.Error)?.retryAtEpochSeconds },
            nativeMutation = { privateKey, publicKey, fingerprint, candidates, referenceTime ->
                NativeCrypto.openPgp.revokeUserId(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = fingerprint,
                    identityId = request.identityId,
                    candidateRevocationKeys = candidates,
                    referenceTimeEpochSeconds = referenceTime,
                )
            },
            toDomain = { result -> result.toDomain() },
        )
    }

    private fun NativeOpenPgpUserIdRevocationResult.toDomain(): GpgUserIdRevocationResult =
        when (this) {
            is NativeOpenPgpUserIdRevocationResult.Success -> {
                withDecodedKeyMaterial(
                    material = keyMaterial,
                    certificateIndex = certificateIndex,
                    certificateArmored = revocationCertificateArmored,
                ) { key, certificate ->
                    GpgUserIdRevocationResult.Success(
                        key = key,
                        revocationCertificateArmored = certificate,
                        changed = changed,
                        effectiveAt = Instant.fromEpochSeconds(effectiveAtEpochSeconds),
                    )
                }
            }

            is NativeOpenPgpUserIdRevocationResult.Error -> {
                GpgUserIdRevocationResult.Error(reason.toDomain(), retryAtEpochSeconds?.let(Instant::fromEpochSeconds))
            }
        }
}
