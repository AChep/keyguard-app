package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactory
import com.artemchep.keyguard.feature.auth.AccountViewRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactory
import com.artemchep.keyguard.feature.changepassword.ChangePasswordRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.datasafety.DataSafetyRouteFactory
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.permissions.PermissionsSettingsRouteFactoryDefault
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.collections.CollectionsRouteFactory
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactory
import com.artemchep.keyguard.feature.home.vault.organizations.OrganizationsRouteFactory
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactory
import com.artemchep.keyguard.feature.license.LicenseRouteFactory
import com.artemchep.keyguard.feature.onboarding.OnboardingRouteFactory
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.feature.privilegedapp.PrivilegedAppListRouteFactory
import com.artemchep.keyguard.feature.send.SendRouteFactory
import com.artemchep.keyguard.feature.send.view.SendViewRouteFactory
import com.artemchep.keyguard.feature.team.AboutTeamRouteFactory
import org.koin.dsl.module

class WearNavigationModule {
    val module = module {
        factory<AccountViewRouteFactory> {
            AccountViewRouteFactoryWear
        }
        factory<BitwardenLoginRouteFactory> {
            BitwardenLoginRouteFactoryWear
        }
        factory<BitwardenLoginTwofaRouteFactory> {
            BitwardenLoginTwofaRouteFactoryWear
        }
        factory<AttachmentPreviewRouteFactory> {
            AttachmentPreviewRouteFactoryWear
        }
        factory<AboutTeamRouteFactory> {
            AboutTeamRouteFactoryWear
        }
        factory<ChangePasswordRouteFactory> {
            ChangePasswordRouteFactoryWear
        }
        factory<ConfirmationRouteFactory> {
            ConfirmationRouteFactoryWear
        }
        factory<DataSafetyRouteFactory> {
            DataSafetyRouteFactoryWear
        }
        factory<AutofillSettingsRouteFactory> {
            AutofillSettingsRouteFactoryWear
        }
        factory<OtherSettingsRouteFactory> {
            OtherSettingsRouteFactoryWear
        }
        factory<CollectionsRouteFactory> {
            CollectionsRouteFactoryWear
        }
        factory<FoldersRouteFactory> {
            FoldersRouteFactoryWear
        }
        factory<LicenseRouteFactory> {
            LicenseRouteFactoryWear
        }
        factory<OnboardingRouteFactory> {
            OnboardingRouteFactoryWear
        }
        factory<SecuritySettingsRouteFactory> {
            SecuritySettingsRouteFactoryWear
        }
        factory<UiSettingsRouteFactory> {
            UiSettingsRouteFactoryWear
        }
        factory<PasskeysCredentialViewRouteFactory> {
            PasskeysCredentialViewRouteFactoryWear
        }
        factory<PrivilegedAppListRouteFactory> {
            PrivilegedAppListRouteFactoryWear
        }
        factory<OrganizationsRouteFactory> {
            OrganizationsRouteFactoryWear
        }
        factory<VaultViewRouteFactory> {
            VaultViewRouteFactoryWear
        }
        factory<VaultRouteFactory> {
            VaultRouteFactoryWear
        }
        factory<SendViewRouteFactory> {
            SendViewRouteFactoryWear
        }
        factory<SendRouteFactory> {
            SendListRouteFactoryWear
        }

        factory<PermissionsSettingsRouteFactory> {
            PermissionsSettingsRouteFactoryDefault
        }
    }
}
