package com.artemchep.keyguard.provider.bitwarden.model

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

data class TwoFactorProvider(
    val type: TwoFactorProviderType,
    val name: StringResource,
    val priority: Int,
    val supported: Boolean = false,
) {
    companion object {
        val state = mapOf(
            TwoFactorProviderType.Authenticator to TwoFactorProvider(
                type = TwoFactorProviderType.Authenticator,
                name = Res.strings.provider_2fa_authenticator,
                priority = 10,
                supported = true,
            ),
            TwoFactorProviderType.YubiKey to TwoFactorProvider(
                type = TwoFactorProviderType.YubiKey,
                name = Res.strings.provider_2fa_yubikey,
                priority = 30,
                supported = true,
            ),
            TwoFactorProviderType.Duo to TwoFactorProvider(
                type = TwoFactorProviderType.Duo,
                name = Res.strings.provider_2fa_duo,
                priority = 20,
                supported = CurrentPlatform is Platform.Mobile,
            ),
            TwoFactorProviderType.OrganizationDuo to TwoFactorProvider(
                type = TwoFactorProviderType.OrganizationDuo,
                name = Res.strings.provider_2fa_duo_organization,
                priority = 21,
                supported = CurrentPlatform is Platform.Mobile,
            ),
            TwoFactorProviderType.Fido2WebAuthn to TwoFactorProvider(
                type = TwoFactorProviderType.Fido2WebAuthn,
                name = Res.strings.provider_2fa_fido2_webauthn,
                priority = 50,
                supported = CurrentPlatform is Platform.Mobile,
            ),
            TwoFactorProviderType.Email to TwoFactorProvider(
                type = TwoFactorProviderType.Email,
                name = Res.strings.provider_2fa_email,
                priority = 0,
                supported = true,
            ),
            // As far as I understand the U2f is replaced by the Fido2WebAuthn
            // provider. Therefore most likely this is never going to be implemented.
            TwoFactorProviderType.U2f to TwoFactorProvider(
                type = TwoFactorProviderType.U2f,
                name = Res.strings.provider_2fa_fido_u2f,
                priority = 40,
                supported = false,
            ),
        )
    }
}
