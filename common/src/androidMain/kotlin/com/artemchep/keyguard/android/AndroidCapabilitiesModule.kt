package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.common.worker.Wrker
import org.koin.dsl.module

/**
 * Workers that depend on the device's Android capabilities. Each app adds
 * its own workers when it builds the `WorkerRegistry`.
 */
class AndroidCapabilityWorkers(val values: List<Wrker>)

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AndroidCredentialCapabilitiesModule(
    config: CredentialProviderPlatformConfig,
) {
    val module = module {
        single<CredentialProviderPlatformConfig> { config }
        single { AndroidCapabilityWorkers(listOf(get<CredentialExchangeRegistrationWorker>())) }
    }
}

class LegacyAndroidCapabilitiesModule {
    val module = module {
        single { AndroidCapabilityWorkers(emptyList()) }
    }
}
