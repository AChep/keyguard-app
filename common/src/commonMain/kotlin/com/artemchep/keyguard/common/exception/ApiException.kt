package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import io.ktor.http.HttpStatusCode

class ApiException(
    val exception: Exception,
    val code: HttpStatusCode,
    val error: String,
    val type: Type?,
    message: String?,
) : HttpException(code, message, exception) {
    sealed interface Type {
        data class CaptchaRequired(
            val siteKey: String,
        ) : Type

        data class TwoFaRequired(
            val providers: List<TwoFactorProviderArgument>,
        ) : Type
    }
}
