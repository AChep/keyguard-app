package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError

data class ChangeGpgKeyExpirationByIdRequest(
    val cipherId: String,
    /** Exact public certificate snapshot for which the change was requested. */
    val expectedPublicKeyArmored: String,
    /** Stored identity hint captured with [expectedPublicKeyArmored]. */
    val expectedKeyFingerprint: String?,
    val change: GpgKeyExpirationChange,
)

sealed interface ChangeGpgKeyExpirationByIdResult {
    data object Success : ChangeGpgKeyExpirationByIdResult

    /** The cipher could not be changed, or the requested source snapshot is no longer current. */
    data class NotChanged(
        val reason: Reason,
    ) : ChangeGpgKeyExpirationByIdResult {
        enum class Reason {
            NotFound,
            NotEditable,
            MissingGpgKey,
            Conflict,
        }
    }

    data class CryptoFailure(
        val reason: GpgKeyExpirationError,
    ) : ChangeGpgKeyExpirationByIdResult
}

interface ChangeGpgKeyExpirationById {
    val isSupported: Boolean

    operator fun invoke(
        request: ChangeGpgKeyExpirationByIdRequest,
    ): IO<ChangeGpgKeyExpirationByIdResult>
}
