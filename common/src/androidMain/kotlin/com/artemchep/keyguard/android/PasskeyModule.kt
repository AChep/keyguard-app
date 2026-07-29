package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistry
import com.artemchep.keyguard.common.worker.Wrker
import org.kodein.di.DI
import org.kodein.di.bindSingleton

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun passkeysModule() = DI.Module(
    name = "passkeys",
) {
    bindSingleton {
        PasskeyBeginGetUnlockFlow(this)
    }
    bindSingleton {
        PasswordProviderGetFlow(this)
    }
    bindSingleton {
        PasskeyProviderGetFlow(this)
    }
    bindSingleton {
        CredentialProviderGetRequestHandler(this)
    }
    bindSingleton {
        PasskeyCreateRequest(this)
    }
    bindSingleton {
        PasskeyBeginGetRequest(this)
    }
    bindSingleton {
        PasswordProviderGetRequest(this)
    }
    bindSingleton {
        PasskeyProviderGetRequest(this)
    }
    bindSingleton {
        PasskeyUtils(this)
    }
    //
    // Credential exchange (CXF/CXP) export
    //
    bindSingleton {
        CredentialExchangeRegistry(this)
    }
    // Bound as the Wrker supertype so it is picked up by
    // `allInstances<Wrker>()` in BaseApp.kt.
    bindSingleton<Wrker> {
        CredentialExchangeRegistrationWorker(this)
    }
}
