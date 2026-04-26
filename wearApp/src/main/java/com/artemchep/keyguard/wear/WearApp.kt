package com.artemchep.keyguard.wear

import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.installFavicons
import com.artemchep.keyguard.android.installVaultKeepAlive
import com.artemchep.keyguard.android.installVaultLock
import com.artemchep.keyguard.android.installWorkers
import com.artemchep.keyguard.android.passkeysModule
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.billing.BillingManagerImpl
import com.artemchep.keyguard.common.di.imageLoaderModule
import com.artemchep.keyguard.common.di.setFromDi
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.core.session.diFingerprintRepositoryModule
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.wear.credential.WearCredentialProviderPlatformConfig
import com.artemchep.keyguard.wear.feature.navigation.wearNavigationModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.android.x.androidXModule
import org.kodein.di.bindSingleton
import org.kodein.di.instance

class WearApp : BaseApp(), org.kodein.di.DIAware {
    override val di by DI.lazy {
        import(androidXModule(this@WearApp))
        import(diFingerprintRepositoryModule())
        if (Build.VERSION.SDK_INT >= 34) {
            import(passkeysModule())
            bindSingleton<CredentialProviderPlatformConfig> {
                WearCredentialProviderPlatformConfig
            }
        }
        import(imageLoaderModule { _ -> })

        // Change the routes to the Wear specific ones.
        import(
            module = wearNavigationModule(),
            allowOverride = true,
        )

        bindSingleton {
            FlavorConfig(
                isFreeAsBeer = true,
                persistVaultData = false,
            )
        }
        bindSingleton<BillingManager> {
            BillingManagerImpl(
                context = this@WearApp,
            )
        }
    }

    override fun onCreate() {
        SingletonImageLoader.setFromDi(di)
        super.onCreate()
        val companionAuthBridge: CompanionAuthBridgeAndroid by instance()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            companionAuthBridge.sweepExpiredArtifacts()
        }
        installWorkers()
        installFavicons()
        installVaultKeepAlive()
        installVaultLock()
    }
}
