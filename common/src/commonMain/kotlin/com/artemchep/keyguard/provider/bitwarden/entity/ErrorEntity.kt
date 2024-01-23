package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.service.state.impl.toMap
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.provider.bitwarden.model.toObj
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorEntity(
    @SerialName("error")
    @JsonNames("message")
    val error: String,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @JsonNames("errorModel")
    @SerialName("ErrorModel")
    val errorModel: Message? = null,
    @JsonNames("validationErrors")
    @SerialName("ValidationErrors")
    val validationErrors: JsonObject? = null,
    //
    // Two factor provider
    //
    @JsonNames("twoFactorProviders")
    @SerialName("TwoFactorProviders")
    val twoFactorProviders: List<TwoFactorProviderTypeEntity> = emptyList(),
    @JsonNames("twoFactorProviders2")
    @SerialName("TwoFactorProviders2")
    val twoFactorProviders2: TwoFactorProviders? = null,
    //
    // Captcha
    //
    @SerialName("HCaptcha_SiteKey")
    val hCaptchaSiteKey: String? = null,
) {
    @Serializable
    data class TwoFactorProviders(
        @SerialName("0")
        val authenticator: AuthenticatorParams? = null,
        @SerialName("1")
        val email: EmailParams? = null,
        @SerialName("2")
        val duo: DuoParams? = null,
        @SerialName("3")
        val yubiKey: YubiKeyParams? = null,
        @SerialName("4")
        val u2f: U2fParams? = null,
        @SerialName("5")
        val remember: RememberParams? = null,
        @SerialName("6")
        val organizationDuo: OrganizationDuoParams? = null,
        @SerialName("7")
        val fido2WebAuthn: JsonElement? = null,
    ) {
        @Serializable
        object AuthenticatorParams

        @Serializable
        data class EmailParams(
            @JsonNames("email")
            @SerialName("Email")
            val email: String? = null,
        )

        @Serializable
        data class DuoParams(
            @JsonNames("host")
            @SerialName("Host")
            val host: String? = null,
            @JsonNames("signature")
            @SerialName("Signature")
            val signature: String? = null,
        )

        @Serializable
        object YubiKeyParams

        @Serializable
        object U2fParams

        @Serializable
        object RememberParams

        @Serializable
        data class OrganizationDuoParams(
            @JsonNames("host")
            @SerialName("Host")
            val host: String? = null,
            @JsonNames("signature")
            @SerialName("Signature")
            val signature: String? = null,
        )
    }

    @Serializable
    data class Message(
        @JsonNames("message")
        @SerialName("Message")
        val message: String,
    )
}

private fun ErrorEntity.TwoFactorProviders.toDomain(): List<TwoFactorProviderArgument> =
    listOfNotNull(
        authenticator?.toDomain(),
        email?.toDomain(),
        duo?.toDomain(),
        yubiKey?.toDomain(),
        u2f?.toDomain(),
        remember?.toDomain(),
        organizationDuo?.toDomain(),
        fido2WebAuthn?.toDomainFido2WebAuthn(),
    )

private fun ErrorEntity.TwoFactorProviders.AuthenticatorParams.toDomain(
) = TwoFactorProviderArgument.Authenticator

private fun ErrorEntity.TwoFactorProviders.EmailParams.toDomain(
) = TwoFactorProviderArgument.Email(
    email = email,
)

private fun ErrorEntity.TwoFactorProviders.DuoParams.toDomain(
) = TwoFactorProviderArgument.Duo(
    host = host,
    signature = signature,
)

private fun ErrorEntity.TwoFactorProviders.YubiKeyParams.toDomain(
) = TwoFactorProviderArgument.YubiKey

private fun ErrorEntity.TwoFactorProviders.U2fParams.toDomain(
) = TwoFactorProviderArgument.U2f

private fun ErrorEntity.TwoFactorProviders.RememberParams.toDomain(
) = TwoFactorProviderArgument.Remember

private fun ErrorEntity.TwoFactorProviders.OrganizationDuoParams.toDomain(
) = TwoFactorProviderArgument.OrganizationDuo(
    host = host,
    signature = signature,
)

private fun JsonElement.toDomainFido2WebAuthn(
) = TwoFactorProviderArgument.Fido2WebAuthn(
    json = this,
)

fun ErrorEntity.toException(
    exception: Exception,
    code: HttpStatusCode,
) = kotlin.run {
    val type = kotlin.run {
        if (hCaptchaSiteKey != null) {
            return@run ApiException.Type.CaptchaRequired(
                siteKey = hCaptchaSiteKey,
            )
        }

        val providers = kotlin.run {
            val new = twoFactorProviders2?.toDomain().orEmpty()
                .groupBy { it::class }
            val old = twoFactorProviders
                .mapNotNull { entity ->
                    entity
                        .toDomain()
                        .toObj()
                }
                .groupBy { it::class }
            // all
            TwoFactorProviderType.entries
                .mapNotNull { type ->
                    val typeObj = type.toObj()
                        ?: return@mapNotNull null
                    val typeClazz = typeObj::class
                    new[typeClazz]?.firstOrNull()
                        ?: old[typeClazz]?.firstOrNull()
                }
        }
        if (providers.isNotEmpty()) {
            return@run ApiException.Type.TwoFaRequired(
                providers = providers,
            )
        }

        // Default exception does not have any additional
        // information.
        null
    }
    // Auto-format the validation error
    // messages to something user-friendly.
    val validationError = validationErrors?.toMap()?.format()
    val message = listOfNotNull(
        errorModel?.message,
        errorDescription,
        error,
        validationError
    ).joinToString(separator = "\n")
    ApiException(
        exception = exception,
        code = code,
        error = error,
        type = type,
        message = message,
    )
}

private fun Any?.format(): String {
    return when (this) {
        is Map<*, *> -> this
            .mapKeys { it.key.toString() }
            .entries
            // Each entry is separated by the extra empty
            // new line.
            .joinToString(
                separator = "\n\n",
            ) { entry ->
                "${entry.key}:\n${entry.value.format()}"
            }

        is Collection<*> -> this
            .joinToString(separator = "\n") { value ->
                "- " + value.format()
            }

        else -> this.toString()
    }
}
