package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import org.kodein.di.DI
import org.kodein.di.bindSingleton

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun passkeysModule() = DI.Module(
    name = "passkeys",
) {
    bindSingleton {
        PasskeyCreateRequest(this)
    }
    bindSingleton {
        PasskeyBeginGetRequest(this)
    }
    bindSingleton {
        PasskeyProviderGetRequest(this)
    }
    bindSingleton {
        PasskeyUtils(this)
    }
    //
    // Generators
    //
    bindSingleton {
        PasskeyGeneratorES256()
    }
}
