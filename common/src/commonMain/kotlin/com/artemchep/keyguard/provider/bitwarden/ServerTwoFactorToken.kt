package com.artemchep.keyguard.provider.bitwarden

import arrow.optics.optics
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType

@optics
data class ServerTwoFactorToken(
    val token: String,
    val provider: TwoFactorProviderType,
    val remember: Boolean = false,
) {
    companion object
}
