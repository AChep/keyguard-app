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
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.PhoneCredentialProviderPlatformConfig
import com.artemchep.keyguard.android.coil3.AppIconFetcher
import com.artemchep.keyguard.android.coil3.AppIconKeyer
import com.artemchep.keyguard.android.downloader.worker.AttachmentDownloadAllWorker
import com.artemchep.keyguard.android.installFavicons
import com.artemchep.keyguard.android.installVaultKeepAlive
import com.artemchep.keyguard.android.installVaultLock
import com.artemchep.keyguard.android.installVaultPersistedSession
import com.artemchep.keyguard.android.installWorkers
import com.artemchep.keyguard.android.ipc.installAndroidIpcProviders
import com.artemchep.keyguard.android.worker.BackupWorker
import com.artemchep.keyguard.android.passkeysModule
import com.artemchep.keyguard.android.util.ShortcutIds
import com.artemchep.keyguard.android.util.ShortcutInfo
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.billing.BillingManagerImpl
import com.artemchep.keyguard.common.di.imageLoaderModule
import com.artemchep.keyguard.common.di.setFromDi
import com.artemchep.keyguard.common.io.*
import com.artemchep.keyguard.common.service.backup.AutomaticBackupPolicy
import com.artemchep.keyguard.common.service.backup.automaticBackupScheduleStateFlow
import com.artemchep.keyguard.common.service.download.DownloadRepository
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.core.session.diFingerprintRepositoryModule
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.kodein.di.*
import org.kodein.di.android.x.androidXModule
import java.util.*
import kotlin.time.ExperimentalTime

class Main : BaseApp(), DIAware {
    override val di by DI.lazy {
        import(androidXModule(this@Main))
        import(diFingerprintRepositoryModule())

        @SuppressLint("NewApi")
        fun importPasskeysModule() {
            import(passkeysModule())
            bindSingleton<CredentialProviderPlatformConfig> {
                PhoneCredentialProviderPlatformConfig
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            importPasskeysModule()
        }
        val imageLoaderModule = kotlin.run {
            val packageManager = packageManager
            imageLoaderModule { directDI ->
                val appIconFetcher = AppIconFetcher.Factory(
                    googlePlayParser = directDI.instance(),
                    packageManager = packageManager,
                    getWebsiteIcons = directDI.instance(),
                )
                add(appIconFetcher)
                add(AppIconKeyer())
            }
        }
        import(imageLoaderModule)
        bindSingleton<BillingManager> {
            BillingManagerImpl(
                context = this@Main,
            )
        }
        bindSingleton {
            FlavorConfig(
                isFreeAsBeer = BuildConfig.FLAVOR == "none",
            )
        }
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
        SingletonImageLoader.setFromDi(di)

        super.onCreate()
        installAndroidIpcProviders()

        val getVaultSession: GetVaultSession by instance()
        val downloadRepository: DownloadRepository by instance()
        val cleanUpAttachment: CleanUpAttachment by instance()
        val sessionReadRepository: SessionReadRepository by instance()
        val companionAuthBridge: CompanionAuthBridgeAndroid = di.direct.instance()

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
            automaticBackupScheduleStateFlow(sessionReadRepository)
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
                            val getCipherFilters: GetCipherFilters = session.di.direct.instance()
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
