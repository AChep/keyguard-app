package com.artemchep.keyguard.di

import org.koin.dsl.module

class VaultModuleCommon {
    val module = module {
        includes(
            VaultOperationsModule().module,
            VaultBitwardenTransportModule().module,
            VaultTransfersModule().module,
            VaultPersistenceModule().module,
            VaultLicensingModule().module,
            VaultHistoryModule().module,
            VaultWatchtowerModule().module,
            VaultSyncModule().module,
        )
    }
}
