package com.artemchep.keyguard.common.service.crypto

/** Atomically self-certifies a new textual User ID and retires an old exact User ID. */
interface GpgUserIdReplacementService {
    val isSupported: Boolean
        get() = true

    fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult
}

object GpgUserIdReplacementServiceUnsupported : GpgUserIdReplacementService {
    override val isSupported: Boolean
        get() = false

    override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult =
        GpgUserIdReplacementResult.Error(
            reason = GpgUserIdReplacementError.UnsupportedPlatform,
        )
}
