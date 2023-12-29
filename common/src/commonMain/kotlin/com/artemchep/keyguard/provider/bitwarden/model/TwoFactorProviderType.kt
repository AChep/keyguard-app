package com.artemchep.keyguard.provider.bitwarden.model

enum class TwoFactorProviderType {
    Unknown,
    Authenticator,
    Email,
    Duo,
    YubiKey,
    U2f,
    Remember,
    OrganizationDuo,
    Fido2WebAuthn,
}

fun TwoFactorProviderType.toObj(): TwoFactorProviderArgument? = when (this) {
    TwoFactorProviderType.Unknown -> null
    TwoFactorProviderType.Authenticator -> TwoFactorProviderArgument.Authenticator
    TwoFactorProviderType.Email -> TwoFactorProviderArgument.Email()
    TwoFactorProviderType.Duo -> TwoFactorProviderArgument.Duo()
    TwoFactorProviderType.YubiKey -> TwoFactorProviderArgument.YubiKey
    TwoFactorProviderType.U2f -> TwoFactorProviderArgument.U2f
    TwoFactorProviderType.Remember -> TwoFactorProviderArgument.Remember
    TwoFactorProviderType.OrganizationDuo -> TwoFactorProviderArgument.OrganizationDuo()
    TwoFactorProviderType.Fido2WebAuthn -> TwoFactorProviderArgument.Fido2WebAuthn()
}
