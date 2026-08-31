package com.artemchep.keyguard.common.service.crypto

/** Creates and applies a signed certification revocation for one exact textual User ID. */
interface GpgUserIdRevocationService {
    val isSupported: Boolean
        get() = true

    fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult
}

object GpgUserIdRevocationServiceUnsupported : GpgUserIdRevocationService {
    override val isSupported: Boolean
        get() = false

    override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
        GpgUserIdRevocationResult.Error(
            reason = GpgUserIdRevocationError.UnsupportedPlatform,
        )
}
