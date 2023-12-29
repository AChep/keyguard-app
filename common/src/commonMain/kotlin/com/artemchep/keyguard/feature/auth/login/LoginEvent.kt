package com.artemchep.keyguard.feature.auth.login

import com.artemchep.keyguard.feature.auth.login.otp.LoginTwofaRoute

/**
 * @author Artem Chepurnyi
 */
sealed interface LoginEvent {
    sealed interface Error : LoginEvent {
        data class OtpRequired(
            val args: LoginTwofaRoute.Args,
        ) : Error
    }
}
