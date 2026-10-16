package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial

sealed interface GpgUserIdChange {
    data class Add(
        val userId: String,
    ) : GpgUserIdChange

    data class MakePrimary(
        val identityId: String,
    ) : GpgUserIdChange
}

data class GpgUserIdUpdateRequest(
    val key: GpgKeyMaterial,
    val change: GpgUserIdChange,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
)

sealed interface GpgUserIdUpdateResult {
    data class Success(
        val key: GpgKeyMaterial,
        val changed: Boolean,
    ) : GpgUserIdUpdateResult

    data class Error(
        val reason: GpgUserIdMutationError,
        val retryAt: kotlin.time.Instant? = null,
    ) : GpgUserIdUpdateResult
}

interface GpgUserIdUpdateService {
    val isSupported: Boolean get() = true

    fun update(request: GpgUserIdUpdateRequest): GpgUserIdUpdateResult
}

object GpgUserIdUpdateServiceUnsupported : GpgUserIdUpdateService {
    override val isSupported: Boolean get() = false

    override fun update(request: GpgUserIdUpdateRequest): GpgUserIdUpdateResult =
        GpgUserIdUpdateResult.Error(GpgUserIdMutationError.UnsupportedPlatform)
}
