package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import io.ktor.http.HttpStatusCode

class ApiException(
    override val title: TextHolder,
    override val text: TextHolder?,
    val exception: Exception,
    val code: HttpStatusCode,
    val error: String,
    val type: Type?,
    message: String?,
    errorCode: String? = null,
    route: String? = null,
) : HttpException(
    statusCode = code,
    m = message,
    e = exception,
    errorCode = errorCode,
    route = route,
), Readable {
    sealed interface Type {
        data class CaptchaRequired(
            val siteKey: String,
        ) : Type

        data class TwoFaRequired(
            val providers: List<TwoFactorProviderArgument>,
        ) : Type
    }
}
