package com.artemchep.keyguard.di

import org.koin.dsl.module

class GlobalModuleCommon {
    val module = module {
        includes(
            ApplicationSerializationModule().module,
            VaultSessionLifecycleModule().module,
            DomainSessionAccessModule().module,
            ApplicationIntegrationsModule().module,
            ApplicationTransfersModule().module,
            ApplicationCryptoModule().module,
            ApplicationLicensingModule().module,
            ApplicationUiModule().module,
            ApplicationAuthenticationModule().module,
            ApplicationGeneratorsModule().module,
            ApplicationSettingsModule().module,
        )
    }
}
