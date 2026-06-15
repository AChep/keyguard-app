package com.artemchep.keyguard.wear.feature.navigation

import com.artemchep.keyguard.feature.auth.AccountViewRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRouteFactory
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactory
import com.artemchep.keyguard.feature.changepassword.ChangePasswordRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.datasafety.DataSafetyRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
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
import org.kodein.di.DI
import org.kodein.di.bindProvider

fun wearNavigationModule() = DI.Module(
    name = "wearNavigationModule",
    allowSilentOverride = true,
) {
    bindProvider<AccountViewRouteFactory> {
        AccountViewRouteFactoryWear
    }
    bindProvider<BitwardenLoginRouteFactory> {
        BitwardenLoginRouteFactoryWear
    }
    bindProvider<BitwardenLoginTwofaRouteFactory> {
        BitwardenLoginTwofaRouteFactoryWear
    }
    bindProvider<AttachmentPreviewRouteFactory> {
        AttachmentPreviewRouteFactoryWear
    }
    bindProvider<AboutTeamRouteFactory> {
        AboutTeamRouteFactoryWear
    }
    bindProvider<ChangePasswordRouteFactory> {
        ChangePasswordRouteFactoryWear
    }
    bindProvider<ConfirmationRouteFactory> {
        ConfirmationRouteFactoryWear
    }
    bindProvider<DataSafetyRouteFactory> {
        DataSafetyRouteFactoryWear
    }
    bindProvider<AutofillSettingsRouteFactory> {
        AutofillSettingsRouteFactoryWear
    }
    bindProvider<OtherSettingsRouteFactory> {
        OtherSettingsRouteFactoryWear
    }
    bindProvider<CollectionsRouteFactory> {
        CollectionsRouteFactoryWear
    }
    bindProvider<FoldersRouteFactory> {
        FoldersRouteFactoryWear
    }
    bindProvider<LicenseRouteFactory> {
        LicenseRouteFactoryWear
    }
    bindProvider<OnboardingRouteFactory> {
        OnboardingRouteFactoryWear
    }
    bindProvider<SecuritySettingsRouteFactory> {
        SecuritySettingsRouteFactoryWear
    }
    bindProvider<UiSettingsRouteFactory> {
        UiSettingsRouteFactoryWear
    }
    bindProvider<PasskeysCredentialViewRouteFactory> {
        PasskeysCredentialViewRouteFactoryWear
    }
    bindProvider<PrivilegedAppListRouteFactory> {
        PrivilegedAppListRouteFactoryWear
    }
    bindProvider<OrganizationsRouteFactory> {
        OrganizationsRouteFactoryWear
    }
    bindProvider<VaultViewRouteFactory> {
        VaultViewRouteFactoryWear
    }
    bindProvider<VaultRouteFactory> {
        VaultRouteFactoryWear
    }
    bindProvider<SendViewRouteFactory> {
        SendViewRouteFactoryWear
    }
    bindProvider<SendRouteFactory> {
        SendListRouteFactoryWear
    }
}
