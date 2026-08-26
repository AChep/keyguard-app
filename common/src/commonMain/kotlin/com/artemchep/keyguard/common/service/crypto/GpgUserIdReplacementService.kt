package com.artemchep.keyguard.common.service.crypto

/** Atomically self-certifies a new textual User ID and retires an old exact User ID. */
interface GpgUserIdReplacementService {
    fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult
}
