package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseManagerImpl
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseSyncer
import com.artemchep.keyguard.common.usecase.GetClaimedLicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.RedeemLicenseKey
import com.artemchep.keyguard.common.usecase.RefreshLicense
import com.artemchep.keyguard.common.usecase.RemoveLicense
import com.artemchep.keyguard.common.usecase.SyncLicense
import com.artemchep.keyguard.common.usecase.impl.GetClaimedLicenseEntitlementImpl
import com.artemchep.keyguard.common.usecase.impl.GetLicenseEntitlementImpl
import com.artemchep.keyguard.common.usecase.impl.GetLicensePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.RedeemLicenseKeyImpl
import com.artemchep.keyguard.common.usecase.impl.RefreshLicenseImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveLicenseImpl
import com.artemchep.keyguard.common.usecase.impl.SyncLicenseImpl
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultLicensingModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<LicenseManagerImpl> {
                LicenseManagerImpl(
                    licenseRepository = get(),
                    decoder = get(),
                    vaultSettingsReadWriteRepository = get(),
                )
            } bind LicenseManager::class

            scoped<LicenseSyncer> {
                LicenseSyncer(
                    licenseManager = get(),
                    refreshLicense = get(),
                    licenseClaimSource = getOrNull(),
                )
            }

            scoped<GetLicenseEntitlementImpl>() bind GetLicenseEntitlement::class

            scoped<GetLicensePremiumImpl>() bind GetLicensePremium::class

            scoped<GetClaimedLicenseEntitlementImpl>() bind GetClaimedLicenseEntitlement::class

            scoped<RedeemLicenseKeyImpl>() bind RedeemLicenseKey::class

            scoped<RefreshLicenseImpl>() bind RefreshLicense::class

            scoped<SyncLicense> {
                SyncLicenseImpl(
                    licenseManager = get(),
                    licenseClaimSource = getOrNull(),
                )
            }

            scoped<RemoveLicenseImpl>() bind RemoveLicense::class
        }
    }
}
