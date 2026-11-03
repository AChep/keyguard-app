package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgUserIdChange
import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdUpdateService
import com.artemchep.keyguard.common.util.sleepBlocking
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdChange
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdUpdateResult
import kotlin.time.Clock

object NativeGpgUserIdUpdateService : GpgUserIdUpdateService {
    override fun update(request: GpgUserIdUpdateRequest): GpgUserIdUpdateResult {
        val change = when (val change = request.change) {
            is GpgUserIdChange.Add -> NativeOpenPgpUserIdChange.Add(change.userId)
            is GpgUserIdChange.MakePrimary -> NativeOpenPgpUserIdChange.MakePrimary(change.identityId)
        }
        return mutateSignedUserId(
            key = request.key,
            candidateRevocationKeys = request.candidateRevocationKeys,
            now = { Clock.System.now() },
            waitForClock = ::sleepBlocking,
            internalFailureError = {
                GpgUserIdUpdateResult.Error(GpgUserIdMutationError.InternalFailure)
            },
            retryAt = { result -> (result as? NativeOpenPgpUserIdUpdateResult.Error)?.retryAtEpochSeconds },
            nativeMutation = { privateKey, publicKey, fingerprint, candidates, referenceTime ->
                NativeCrypto.openPgp.updateUserId(
                    privateKey,
                    publicKey,
                    fingerprint,
                    change,
                    candidates,
                    referenceTime,
                )
            },
            toDomain = { result ->
                when (result) {
                    is NativeOpenPgpUserIdUpdateResult.Success -> {
                        result.keyMaterial
                            .useAsGpgKeyMaterial(result.certificateIndex) { key ->
                                GpgUserIdUpdateResult.Success(key, result.changed)
                            }
                    }

                    is NativeOpenPgpUserIdUpdateResult.Error -> {
                        GpgUserIdUpdateResult.Error(
                            reason = result.reason.toDomain(),
                            retryAt = result.retryAtEpochSeconds?.let(kotlin.time.Instant::fromEpochSeconds),
                        )
                    }
                }
            },
        )
    }
}
