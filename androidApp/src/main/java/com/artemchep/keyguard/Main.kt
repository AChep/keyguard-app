package com.artemchep.keyguard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import com.artemchep.bindin.bindBlock
import com.artemchep.keyguard.android.AndroidCredentialCapabilitiesModule
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.LegacyAndroidCapabilitiesModule
import com.artemchep.keyguard.android.PasskeysModule
import com.artemchep.keyguard.android.PhoneCredentialProviderPlatformConfig
import com.artemchep.keyguard.android.coil3.AppIconFetcher
import com.artemchep.keyguard.android.coil3.AppIconKeyer
import com.artemchep.keyguard.android.credentialexchange.CredentialExchangeRegistrationWorker
import com.artemchep.keyguard.android.downloader.worker.AttachmentDownloadAllWorker
import com.artemchep.keyguard.android.installFavicons
import com.artemchep.keyguard.android.installVaultKeepAlive
import com.artemchep.keyguard.android.installVaultLock
import com.artemchep.keyguard.android.installVaultPersistedSession
import com.artemchep.keyguard.android.installWorkers
import com.artemchep.keyguard.android.ipc.AndroidIpcModule
import com.artemchep.keyguard.android.ipc.installAndroidIpcProviders
import com.artemchep.keyguard.android.util.ShortcutIds
import com.artemchep.keyguard.android.util.ShortcutInfo
import com.artemchep.keyguard.android.worker.BackupWorker
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.billing.BillingManagerImpl
import com.artemchep.keyguard.common.di.ImageLoadingModule
import com.artemchep.keyguard.common.di.setFromKoin
import com.artemchep.keyguard.common.io.*
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationServiceNone
import com.artemchep.keyguard.common.service.backup.AutomaticBackupPolicy
import com.artemchep.keyguard.common.service.backup.automaticBackupScheduleStateFlow
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.core.session.PlatformApplicationModule
import com.artemchep.keyguard.core.session.usecase.PlatformVaultModule
import com.artemchep.keyguard.di.GlobalModuleCommon
import com.artemchep.keyguard.di.VaultModuleCommon
import com.artemchep.keyguard.di.resolve
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.navigation.NavigationModule
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactory
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactoryAndroid
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal fun createPhoneKoinApplication(
    application: () -> android.app.Application,
    sdkInt: Int = Build.VERSION.SDK_INT,
): org.koin.core.KoinApplication {
    val applicationModule = PhoneApplicationModule(application)
    val images = ImageLoadingModule { scope ->
        add(
            AppIconFetcher.Factory(
                googlePlayParser = scope.get(),
                packageManager = application().packageManager,
                getWebsiteIcons = scope.get(),
            ),
        )
        add(AppIconKeyer())
    }
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
                NavigationModule().module,
                AndroidIpcModule().module,
                applicationModule.module,
                images.module,
                PasskeysModule().module,
                AndroidCredentialCapabilitiesModule(PhoneCredentialProviderPlatformConfig).module,
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
                NavigationModule().module,
                AndroidIpcModule().module,
                applicationModule.module,
                images.module,
                LegacyAndroidCapabilitiesModule().module,
            )
        }
    }
}

private class PhoneApplicationModule(
    application: () -> android.app.Application,
) {
    val module = module {
        single<android.app.Application> { application() }
        single<android.content.Context> { get<android.app.Application>() }
        single<BillingManager> { BillingManagerImpl(application()) }
        single { FlavorConfig(isFreeAsBeer = BuildConfig.FLAVOR == "none") }
        factory<ScanQrRouteFactory> { ScanQrRouteFactoryAndroid }
    }
}

class Main : BaseApp() {
    override val koinApplication by lazy {
        createPhoneKoinApplication(application = { this })
    }

    // See:
    // https://issuetracker.google.com/issues/243457462
    override fun attachBaseContext(base: Context) {
        val updatedContext = ContextCompat.getContextForLanguage(base)

        // Update locale only if needed.
        val updatedLocale: Locale =
            updatedContext.resources.configuration.locale
        if (!Locale.getDefault().equals(updatedLocale)) {
            Locale.setDefault(updatedLocale)
        }
        super.attachBaseContext(updatedContext)
    }

    @OptIn(ExperimentalTime::class)
    override fun onCreate() {
        // Construct the image loader singleton to match what
        // we have set in the application's DI.
        SingletonImageLoader.setFromKoin(koin)

        super.onCreate()
        installAndroidIpcProviders()

        val getVaultSession: GetVaultSession by lazy { koin.get() }
        val downloadRepository: DownloadRepository by lazy { koin.get() }
        val cleanUpAttachment: CleanUpAttachment by lazy { koin.get() }
        val sessionReadRepository: SessionReadRepository by lazy { koin.get() }
        val companionAuthBridge: CompanionAuthBridgeAndroid = koin.get()

        installWorkers()
        installFavicons()
        installVaultPersistedSession()
        installVaultKeepAlive()
        installVaultLock()

        val processLifecycleOwner = ProcessLifecycleOwner.get()
        processLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            companionAuthBridge.sweepExpiredArtifacts()
        }

        AttachmentDownloadAllWorker.enqueue(this)
        processLifecycleOwner.lifecycleScope.launch {
            automaticBackupScheduleStateFlow(sessionReadRepository, koin.get())
                .collectLatest { state ->
                    if (!state.shouldRun) {
                        BackupWorker.cancel(this@Main)
                        return@collectLatest
                    }

                    delay(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
                    BackupWorker.enqueueOnce(
                        context = this@Main,
                        config = state.config,
                    )
                }
        }

        // attachment clean-up
        ProcessLifecycleOwner.get().bindBlock {
            coroutineScope {
                CleanUpAttachmentImpl.zzz(
                    scope = this,
                    downloadRepository = downloadRepository,
                    cleanUpAttachment = cleanUpAttachment,
                )
            }
        }

        // shortcuts
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            getVaultSession()
                .flatMapLatest { session ->
                    when (session) {
                        is MasterSession.Key -> {
                            val getCipherFilters = session.session.resolve { get<GetCipherFilters>() }
                                ?: return@flatMapLatest emptyFlow()
                            getCipherFilters()
                        }

                        is MasterSession.Empty -> emptyFlow()
                    }
                }
                .onEach { filters ->
                    val dynamicShortcutsIdsToRemove = kotlin.run {
                        val oldDynamicShortcutsIds =
                            ShortcutManagerCompat.getDynamicShortcuts(this@Main)
                                .map { it.id }
                                .toSet()
                        val newDynamicShortcutsIds = filters
                            .map { ShortcutIds.forFilter(it.id) }
                            .toSet()
                        oldDynamicShortcutsIds - newDynamicShortcutsIds
                    }
                    if (dynamicShortcutsIdsToRemove.isNotEmpty()) {
                        val ids = dynamicShortcutsIdsToRemove.toList()
                        ShortcutManagerCompat.removeDynamicShortcuts(this@Main, ids)
                    }

                    val shortcuts = filters
                        .map { filter ->
                            ShortcutInfo.forFilter(
                                context = this@Main,
                                filter = filter,
                            )
                        }
                        .take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(this@Main))
                    // The shortcut activation is reported from MainActivity in :common;
                    // app-module lint cannot correlate that cross-module call site.
                    @Suppress("ReportShortcutUsage")
                    ShortcutManagerCompat.addDynamicShortcuts(this@Main, shortcuts)
                }
                .launchIn(this)
        }
    }
}
