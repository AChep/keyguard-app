package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.feature.auth.AccountViewRouteFactory
import com.artemchep.keyguard.feature.auth.AccountViewRouteFactoryDefault
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactoryDefault
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactoryDefault
import com.artemchep.keyguard.feature.changepassword.ChangePasswordRouteFactory
import com.artemchep.keyguard.feature.changepassword.ChangePasswordRouteFactoryDefault
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import com.artemchep.keyguard.feature.datasafety.DataSafetyRouteFactory
import com.artemchep.keyguard.feature.datasafety.DataSafetyRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRouteFactory
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactory
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRouteFactory
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactory
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactoryDefault
import com.artemchep.keyguard.feature.license.LicenseRouteFactory
import com.artemchep.keyguard.feature.license.LicenseRouteFactoryDefault
import com.artemchep.keyguard.feature.onboarding.OnboardingRouteFactory
import com.artemchep.keyguard.feature.onboarding.OnboardingRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactoryDefault
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRouteFactory
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRouteFactoryDefault
import com.artemchep.keyguard.feature.send.SendRouteFactory
import com.artemchep.keyguard.feature.send.SendRouteFactoryDefault
import com.artemchep.keyguard.feature.send.view.SendViewRouteFactory
import com.artemchep.keyguard.feature.send.view.SendViewRouteFactoryDefault
import com.artemchep.keyguard.feature.team.AboutTeamRouteFactory
import com.artemchep.keyguard.feature.team.AboutTeamRouteFactoryDefault
import org.kodein.di.DI
import org.kodein.di.bindProvider

fun defaultNavigationModule() = DI.Module(
    name = "defaultNavigationModule",
) {
    bindProvider<AccountViewRouteFactory> {
        AccountViewRouteFactoryDefault
    }
    bindProvider<BitwardenLoginRouteFactory> {
        BitwardenLoginRouteFactoryDefault
    }
    bindProvider<BitwardenLoginTwofaRouteFactory> {
        BitwardenLoginTwofaRouteFactoryDefault
    }
    bindProvider<AboutTeamRouteFactory> {
        AboutTeamRouteFactoryDefault
    }
    bindProvider<ChangePasswordRouteFactory> {
        ChangePasswordRouteFactoryDefault
    }
    bindProvider<ConfirmationRouteFactory> {
        ConfirmationRouteFactoryDefault
    }
    bindProvider<DataSafetyRouteFactory> {
        DataSafetyRouteFactoryDefault
    }
    bindProvider<CollectionsRouteFactory> {
        CollectionsRouteFactoryDefault
    }
    bindProvider<FoldersRouteFactory> {
        FoldersRouteFactoryDefault
    }
    bindProvider<LicenseRouteFactory> {
        LicenseRouteFactoryDefault
    }
    bindProvider<OnboardingRouteFactory> {
        OnboardingRouteFactoryDefault
    }
    bindProvider<AutofillSettingsRouteFactory> {
        AutofillSettingsRouteFactoryDefault
    }
    bindProvider<OtherSettingsRouteFactory> {
        OtherSettingsRouteFactoryDefault
    }
    bindProvider<OrganizationsRouteFactory> {
        OrganizationsRouteFactoryDefault
    }
    bindProvider<PermissionsSettingsRouteFactory> {
        PermissionsSettingsRouteFactoryDefault
    }
    bindProvider<SecuritySettingsRouteFactory> {
        SecuritySettingsRouteFactoryDefault
    }
    bindProvider<UiSettingsRouteFactory> {
        UiSettingsRouteFactoryDefault
    }
    bindProvider<PasskeysCredentialViewRouteFactory> {
        PasskeysCredentialViewRouteFactoryDefault
    }
    bindProvider<PrivilegedAppListRouteFactory> {
        PrivilegedAppListRouteFactoryDefault
    }
    bindProvider<VaultViewRouteFactory> {
        VaultViewRouteFactoryDefault
    }
    bindProvider<VaultRouteFactory> {
        VaultRouteFactoryDefault
    }
    bindProvider<SendRouteFactory> {
        SendRouteFactoryDefault
    }
    bindProvider<SendViewRouteFactory> {
        SendViewRouteFactoryDefault
    }
}
