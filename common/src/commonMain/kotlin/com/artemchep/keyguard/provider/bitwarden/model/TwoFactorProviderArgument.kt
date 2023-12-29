package com.artemchep.keyguard.provider.bitwarden.model

import kotlinx.serialization.json.JsonElement

sealed interface TwoFactorProviderArgument {
    val type: TwoFactorProviderType

    data object Authenticator : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.Authenticator
    }

    data class Email(
        val email: String? = null,
    ) : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.Email
    }

    data class Duo(
        val host: String? = null,
        val signature: String? = null,
    ) : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.Duo
    }

    data object YubiKey : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.YubiKey
    }

    data object U2f : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.U2f
    }

    data object Remember : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.Remember
    }

    data class OrganizationDuo(
        val host: String? = null,
        val signature: String? = null,
    ) : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.OrganizationDuo
    }

    data class Fido2WebAuthn(
        val json: JsonElement? = null,
    ) : TwoFactorProviderArgument {
        override val type get() = TwoFactorProviderType.Fido2WebAuthn
    }
}
