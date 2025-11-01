package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute

/**
 * @author Artem Chepurnyi
 */
sealed interface BitwardenLoginEvent {
    sealed interface Error : BitwardenLoginEvent {
        data class OtpRequired(
            val args: BitwardenLoginTwofaRoute.Args,
        ) : Error
    }
}