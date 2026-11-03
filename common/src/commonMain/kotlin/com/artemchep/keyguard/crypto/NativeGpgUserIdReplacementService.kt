package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementService
import com.artemchep.keyguard.common.util.sleepBlocking
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdReplacementResult
import com.artemchep.keyguard.nativecrypto.isValidOpenPgpUserId
import kotlin.time.Clock
import kotlin.time.Instant

object NativeGpgUserIdReplacementService : GpgUserIdReplacementService {
    override val isSupported: Boolean
        get() = true

    override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult =
        replace(
            request = request,
            now = { Clock.System.now() },
            waitForClock = ::sleepBlocking,
        )

    internal fun replace(
        request: GpgUserIdReplacementRequest,
        now: () -> Instant,
        waitForClock: (milliseconds: Long) -> Boolean,
    ): GpgUserIdReplacementResult {
        val validationError =
            when {
                request.key.privateKeyArmored.isBlank() -> GpgUserIdMutationError.EmptyPrivateKey
                !request.newUserId.isValidOpenPgpUserId() -> GpgUserIdMutationError.InvalidNewUserId
                else -> null
            }
        if (validationError != null) {
            return GpgUserIdReplacementResult.Error(validationError)
        }
        return mutateSignedUserId(
            key = request.key,
            candidateRevocationKeys = request.candidateRevocationKeys,
            now = now,
            waitForClock = waitForClock,
            internalFailureError = {
                GpgUserIdReplacementResult.Error(GpgUserIdMutationError.InternalFailure)
            },
            retryAt = { result -> (result as? NativeOpenPgpUserIdReplacementResult.Error)?.retryAtEpochSeconds },
            nativeMutation = { privateKey, publicKey, fingerprint, candidates, referenceTime ->
                NativeCrypto.openPgp.replaceUserId(
                    privateKey = privateKey,
                    publicKey = publicKey,
                    expectedPrimaryFingerprint = fingerprint,
                    oldIdentityId = request.oldIdentityId,
                    newUserId = request.newUserId,
                    candidateRevocationKeys = candidates,
                    referenceTimeEpochSeconds = referenceTime,
                )
            },
            toDomain = { result -> result.toDomain() },
        )
    }

    private fun NativeOpenPgpUserIdReplacementResult.toDomain(): GpgUserIdReplacementResult =
        when (this) {
            is NativeOpenPgpUserIdReplacementResult.Success -> {
                withDecodedKeyMaterial(
                    material = keyMaterial,
                    certificateIndex = certificateIndex,
                    certificateArmored = replacementCertificateArmored,
                ) { key, certificate ->
                    GpgUserIdReplacementResult.Success(
                        key = key,
                        replacementCertificateArmored = certificate,
                        changed = changed,
                        effectiveAt = Instant.fromEpochSeconds(effectiveAtEpochSeconds),
                        oldIdentityId = oldIdentityId,
                        newIdentityId = newIdentityId,
                        primaryUserId = primaryUserId,
                    )
                }
            }

            is NativeOpenPgpUserIdReplacementResult.Error -> {
                GpgUserIdReplacementResult.Error(reason.toDomain(), retryAtEpochSeconds?.let(Instant::fromEpochSeconds))
            }
        }
}
