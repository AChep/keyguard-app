package com.artemchep.keyguard.common.service.crypto

/** Creates and applies a signed certification revocation for one exact textual User ID. */
interface GpgUserIdRevocationService {
    fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult
}
