package com.artemchep.keyguard

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import com.artemchep.bindin.bindBlock
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.android.coil3.AppIconFetcher
import com.artemchep.keyguard.android.coil3.AppIconKeyer
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.worker.AttachmentDownloadAllWorker
import com.artemchep.keyguard.android.passkeysModule
import com.artemchep.keyguard.android.util.ShortcutIds
import com.artemchep.keyguard.android.util.ShortcutInfo
import com.artemchep.keyguard.billing.BillingManager
import com.artemchep.keyguard.billing.BillingManagerImpl
import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.di.imageLoaderModule
import com.artemchep.keyguard.common.di.setFromDi
import com.artemchep.keyguard.common.io.*
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.common.usecase.impl.CleanUpAttachmentImpl
import com.artemchep.keyguard.core.session.diFingerprintRepositoryModule
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.toCommon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import org.kodein.di.*
import org.kodein.di.android.x.androidXModule
import java.util.*
import kotlin.time.ExperimentalTime

class Main : BaseApp(), DIAware {
    override val di by DI.lazy {
        import(androidXModule(this@Main))
        import(diFingerprintRepositoryModule())
        if (Build.VERSION.SDK_INT >= 34) {
            import(passkeysModule())
        }
        val imageLoaderModule = kotlin.run {
            val packageManager = packageManager
            imageLoaderModule {
                add(AppIconFetcher.Factory(packageManager))
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
        val logRepository: LogRepository by instance()
        val getVaultSession: GetVaultSession by instance()
        val putVaultSession: PutVaultSession by instance()
        val getVaultPersist: GetVaultPersist by instance()
        val keyReadWriteRepository: KeyReadWriteRepository by instance()
        val clearVaultSession: ClearVaultSession by instance()
        val downloadRepository: DownloadRepository by instance()
        val cleanUpAttachment: CleanUpAttachment by instance()
        val appWorker: AppWorker by instance(tag = AppWorker.Feature.SYNC)
//        val cleanUpDownload: CleanUpDownloadImpl by instance()

        val processLifecycleOwner = ProcessLifecycleOwner.get()
        val processLifecycle = processLifecycleOwner.lifecycle
        val processLifecycleFlow = processLifecycle
            .currentStateFlow
            // Convert to platform agnostic
            // lifecycle state.
            .map { state ->
                state.toCommon()
            }
        processLifecycleOwner.lifecycleScope.launch {
            appWorker.launch(this, processLifecycleFlow)
        }

        AttachmentDownloadAllWorker.enqueue(this)

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

        val workers by allInstances<Wrker>()
        workers.forEach {
            ProcessLifecycleOwner.get().bindBlock {
                coroutineScope {
                    it.start(this, processLifecycleFlow)
                }
            }
        }

        // favicon
        ProcessLifecycleOwner.get().bindBlock {
            getVaultSession()
                .map { session ->
                    val key = session as? MasterSession.Key
                    key?.di?.direct?.instance<GetAccounts>()
                }
                .collectLatest { getAccounts ->
                    if (getAccounts != null) {
                        coroutineScope {
                            Favicon.launch(this, getAccounts)
                        }
                    }
                }
        }

        // persist
        ProcessLifecycleOwner.get().bindBlock {
            combine(
                getVaultSession(),
                getVaultPersist(),
            ) { session, persist ->
                val persistedMasterKey = if (persist && session is MasterSession.Key) {
                    session.masterKey
                } else {
                    null
                }
                persistedMasterKey
            }
                .onEach { masterKey ->
                    val persistedSession = if (masterKey != null) {
                        PersistedSession(
                            masterKey = masterKey,
                            createdAt = Clock.System.now(),
                            persistedAt = Clock.System.now(),
                        )
                    } else {
                        null
                    }
                    keyReadWriteRepository.put(persistedSession)
                        .attempt()
                        .bind()
                }
                .collect()
        }

        // timeout
        val vaultSessionLocker: VaultSessionLocker by instance()
        ProcessLifecycleOwner.get().bindBlock {
            vaultSessionLocker.keepAlive()
        }

        // screen lock
        val getVaultLockAfterScreenOff: GetVaultLockAfterScreenOff by instance()
        val powerService: PowerService by instance()
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val screenFlow = powerService
                .getScreenState()
                .map { screen ->
                    val instant = Clock.System.now()
                    instant to screen
                }
                .shareIn(this, SharingStarted.Eagerly, replay = 1)
            getVaultLockAfterScreenOff()
                .flatMapLatest { screenLock ->
                    if (screenLock) {
                        getVaultSession()
                    } else {
                        val emptyVaultSession = MasterSession.Empty()
                        flowOf(emptyVaultSession)
                    }
                }
                .map { session ->
                    when (session) {
                        is MasterSession.Key -> true
                        is MasterSession.Empty -> false
                    }
                }
                .distinctUntilChanged()
                .map { sessionExists ->
                    val instant = Clock.System.now()
                    instant to sessionExists
                }
                .flatMapLatest { (sessionTimestamp, sessionExists) ->
                    if (sessionExists) {
                        return@flatMapLatest screenFlow
                            .mapNotNull { (screenTimestamp, screen) ->
                                screen
                                    .takeIf { screenTimestamp > sessionTimestamp }
                            }
                    }

                    emptyFlow()
                }
                .filter { it is Screen.Off }
                .onEach {
                    val lockReason = TextHolder.Res(Res.string.lock_reason_screen_off)
                    clearVaultSession(LockReason.TIMEOUT, lockReason)
                        .attempt()
                        .launchIn(this)
                }
                .launchIn(this)
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
                    ShortcutManagerCompat.addDynamicShortcuts(this@Main, shortcuts)
                }
                .launchIn(this)
        }
    }
}
