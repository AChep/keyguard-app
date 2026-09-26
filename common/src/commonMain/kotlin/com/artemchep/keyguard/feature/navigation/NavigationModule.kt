package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactory
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactoryDefault
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
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactoryDefault
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
import org.koin.dsl.module

class NavigationModule {
    val module = module {
        factory<AccountViewRouteFactory> {
            AccountViewRouteFactoryDefault
        }
        factory<BitwardenLoginRouteFactory> {
            BitwardenLoginRouteFactoryDefault
        }
        factory<BitwardenLoginTwofaRouteFactory> {
            BitwardenLoginTwofaRouteFactoryDefault
        }
        factory<AttachmentPreviewRouteFactory> {
            AttachmentPreviewRouteFactoryDefault
        }
        factory<AboutTeamRouteFactory> {
            AboutTeamRouteFactoryDefault
        }
        factory<ChangePasswordRouteFactory> {
            ChangePasswordRouteFactoryDefault
        }
        factory<ConfirmationRouteFactory> {
            ConfirmationRouteFactoryDefault
        }
        factory<DataSafetyRouteFactory> {
            DataSafetyRouteFactoryDefault
        }
        factory<CollectionsRouteFactory> {
            CollectionsRouteFactoryDefault
        }
        factory<FoldersRouteFactory> {
            FoldersRouteFactoryDefault
        }
        factory<LicenseRouteFactory> {
            LicenseRouteFactoryDefault
        }
        factory<OnboardingRouteFactory> {
            OnboardingRouteFactoryDefault
        }
        factory<AutofillSettingsRouteFactory> {
            AutofillSettingsRouteFactoryDefault
        }
        factory<OtherSettingsRouteFactory> {
            OtherSettingsRouteFactoryDefault
        }
        factory<OrganizationsRouteFactory> {
            OrganizationsRouteFactoryDefault
        }
        factory<PermissionsSettingsRouteFactory> {
            PermissionsSettingsRouteFactoryDefault
        }
        factory<SecuritySettingsRouteFactory> {
            SecuritySettingsRouteFactoryDefault
        }
        factory<UiSettingsRouteFactory> {
            UiSettingsRouteFactoryDefault
        }
        factory<PasskeysCredentialViewRouteFactory> {
            PasskeysCredentialViewRouteFactoryDefault
        }
        factory<PrivilegedAppListRouteFactory> {
            PrivilegedAppListRouteFactoryDefault
        }
        factory<VaultViewRouteFactory> {
            VaultViewRouteFactoryDefault
        }
        factory<VaultRouteFactory> {
            VaultRouteFactoryDefault
        }
        factory<SendRouteFactory> {
            SendRouteFactoryDefault
        }
        factory<SendViewRouteFactory> {
            SendViewRouteFactoryDefault
        }
    }
}
