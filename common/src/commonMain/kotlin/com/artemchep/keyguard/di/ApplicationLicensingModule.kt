package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.common.service.license.impl.LicenseServiceImpl
import com.artemchep.keyguard.common.service.licensekey.LicenseEntitlementProofVerifier
import com.artemchep.keyguard.common.service.licensekey.LicenseRepository
import com.artemchep.keyguard.common.service.licensekey.LicenseServerConfig
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseKeyDecoder
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseRepositoryImpl
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.common.usecase.impl.GetCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetDebugPremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSessionLicensePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugPremiumImpl
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class ApplicationLicensingModule {
    val module = module {
        single<LicenseServerConfig> {
            LicenseServerConfig.Default
        }

        single<Kg2LicenseKeyDecoder> {
            Kg2LicenseKeyDecoder(
                signatureVerifier = get(),
            )
        }

        single<LicenseEntitlementProofVerifier> {
            LicenseEntitlementProofVerifier(
                signatureVerifier = get(),
                json = get(),
            )
        }

        single<LicenseRepository> {
            LicenseRepositoryImpl(
                httpClient = get(qualifier = named("curl")),
                config = get(),
                cryptoGenerator = get(),
                proofVerifier = get(),
            )
        }

        single<GetVaultSessionLicensePremiumImpl>() bind GetLicensePremium::class

        single<GetCachePremiumImpl>() bind GetCachePremium::class

        single<PutCachePremiumImpl>() bind PutCachePremium::class

        single<GetDebugPremiumImpl>() bind GetDebugPremium::class

        single<PutDebugPremiumImpl>() bind PutDebugPremium::class

        single<LicenseServiceImpl>() bind LicenseService::class
    }
}
