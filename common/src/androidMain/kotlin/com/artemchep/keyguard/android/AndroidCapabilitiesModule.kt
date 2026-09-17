package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.common.worker.WorkerRegistry
import org.koin.dsl.module

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AndroidCredentialCapabilitiesModule(
    config: CredentialProviderPlatformConfig,
) {
    val module = module {
        single<CredentialProviderPlatformConfig> { config }
        single { WorkerRegistry(listOf(get<CredentialExchangeRegistrationWorker>())) }
    }
}

class LegacyAndroidCapabilitiesModule {
    val module = module {
        single { WorkerRegistry(emptyList()) }
    }
}
