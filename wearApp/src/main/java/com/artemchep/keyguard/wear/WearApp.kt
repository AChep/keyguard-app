package com.artemchep.keyguard.wear

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import com.artemchep.keyguard.android.AndroidCredentialCapabilitiesModule
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.LegacyAndroidCapabilitiesModule
import com.artemchep.keyguard.android.PasskeysModule
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.android.installFavicons
import com.artemchep.keyguard.android.installVaultKeepAlive
import com.artemchep.keyguard.android.installVaultLock
import com.artemchep.keyguard.android.installWorkers
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.billing.BillingManagerImpl
import com.artemchep.keyguard.common.di.ImageLoadingModule
import com.artemchep.keyguard.common.di.setFromKoin
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.core.session.PlatformApplicationModule
import com.artemchep.keyguard.core.session.usecase.PlatformVaultModule
import com.artemchep.keyguard.di.GlobalModuleCommon
import com.artemchep.keyguard.di.VaultModuleCommon
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.navigation.NavigationModule
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.wear.locale.WearLocaleController
import com.artemchep.keyguard.wear.locale.WearLocaleModule
import com.artemchep.keyguard.wear.credential.WearCredentialProviderPlatformConfig
import com.artemchep.keyguard.wear.feature.navigation.WearNavigationModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal fun createWearKoinApplication(
    application: () -> android.app.Application,
    sdkInt: Int = Build.VERSION.SDK_INT,
): org.koin.core.KoinApplication {
    val applicationModule = WearApplicationModule(application)
    val images = ImageLoadingModule { }
    // Both branches spell out the full module list on purpose. The Koin compiler
    // validates only literal modules(...) calls, so a shared list, spread, or helper
    // would drop this graph from compile-time checking.
    return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        @SuppressLint("NewApi")
        koinApplication {
            allowOverride(false)
            modules(
                GlobalModuleCommon().module,
                VaultModuleCommon().module,
                PlatformVaultModule().module,
                PlatformApplicationModule().module,
                WearLocaleModule().module,
                WearNavigationModule().module,
                applicationModule.module,
                images.module,
                PasskeysModule().module,
                AndroidCredentialCapabilitiesModule(WearCredentialProviderPlatformConfig).module,
            )
        }
    } else {
        koinApplication {
            allowOverride(false)
            modules(
                GlobalModuleCommon().module,
                VaultModuleCommon().module,
                PlatformVaultModule().module,
                PlatformApplicationModule().module,
                WearLocaleModule().module,
                WearNavigationModule().module,
                applicationModule.module,
                images.module,
                LegacyAndroidCapabilitiesModule().module,
            )
        }
    }
}

private class WearApplicationModule(
    application: () -> android.app.Application,
) {
    val module = module {
        single<android.app.Application> { application() }
        single<android.content.Context> { get<android.app.Application>() }
        single<BillingManager> { BillingManagerImpl(application()) }
        single { FlavorConfig(isFreeAsBeer = true) }
        single<AndroidIpcRegistrationService> { AndroidIpcRegistrationServiceNone }
    }
}

class WearApp : BaseApp() {
    override val koinApplication by lazy {
        createWearKoinApplication(application = { this })
    }

    private val localeController: WearLocaleController by lazy { koin.get() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        localeController.refresh()
    }

    override fun onCreate() {
        super.onCreate()
        // Also runs for service-only starts. A failed migration remains pending for the next start.
        runCatching { localeController.initialize() }.onFailure(::recordException)
        SingletonImageLoader.setFromKoin(koin)
        val companionAuthBridge: CompanionAuthBridgeAndroid by lazy { koin.get() }
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            companionAuthBridge.sweepExpiredArtifacts()
        }
        installWorkers()
        installFavicons()
        installVaultKeepAlive()
        installVaultLock()
    }
}
