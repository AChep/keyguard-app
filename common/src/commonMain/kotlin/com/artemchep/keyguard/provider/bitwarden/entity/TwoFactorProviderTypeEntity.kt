package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TwoFactorProviderTypeEntity {
    @SerialName("-1")
    Unknown,

    @SerialName("0")
    Authenticator,

    @SerialName("1")
    Email,

    @SerialName("2")
    Duo,

    @SerialName("3")
    YubiKey,

    @SerialName("4")
    U2f,

    @SerialName("5")
    Remember,

    @SerialName("6")
    OrganizationDuo,

    @SerialName("7")
    Fido2WebAuthn,

    ;

    companion object {
        fun of(type: TwoFactorProviderType) = when (type) {
            TwoFactorProviderType.Unknown -> Unknown
            TwoFactorProviderType.Authenticator -> Authenticator
            TwoFactorProviderType.Email -> Email
            TwoFactorProviderType.EmailNewDevice -> Unknown
            TwoFactorProviderType.Duo -> Duo
            TwoFactorProviderType.YubiKey -> YubiKey
            TwoFactorProviderType.U2f -> U2f
            TwoFactorProviderType.Remember -> Remember
            TwoFactorProviderType.OrganizationDuo -> OrganizationDuo
            TwoFactorProviderType.Fido2WebAuthn -> Fido2WebAuthn
        }
    }
}

fun TwoFactorProviderTypeEntity.toDomain(): TwoFactorProviderType = when (this) {
    TwoFactorProviderTypeEntity.Unknown -> TwoFactorProviderType.Unknown
    TwoFactorProviderTypeEntity.Authenticator -> TwoFactorProviderType.Authenticator
    TwoFactorProviderTypeEntity.Email -> TwoFactorProviderType.Email
    TwoFactorProviderTypeEntity.Duo -> TwoFactorProviderType.Duo
    TwoFactorProviderTypeEntity.YubiKey -> TwoFactorProviderType.YubiKey
    TwoFactorProviderTypeEntity.U2f -> TwoFactorProviderType.U2f
    TwoFactorProviderTypeEntity.Remember -> TwoFactorProviderType.Remember
    TwoFactorProviderTypeEntity.OrganizationDuo -> TwoFactorProviderType.OrganizationDuo
    TwoFactorProviderTypeEntity.Fido2WebAuthn -> TwoFactorProviderType.Fido2WebAuthn
}
