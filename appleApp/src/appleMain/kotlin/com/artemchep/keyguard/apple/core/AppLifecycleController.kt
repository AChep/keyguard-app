package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.copy.AppleManagedImportFiles
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.lock_reason_manually
import kotlin.time.Clock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import platform.Foundation.NSUserDefaults

/**
 * App-wide lifecycle: the process-wide startup wiring, the scene-phase mirror, the vault status and
 * "lock now". Its [init], run when [KeyguardCore] constructs this controller in the main app, is the
 * Apple analogue of the desktop app's `Main.kt` bootstrap.
 */
internal class AppLifecycleController(
    private val ctx: CoreContext,
) {
    private val getVaultSession: GetVaultSession by lazy { ctx.koin.get() }
    private val getVaultPersist: GetVaultPersist by lazy { ctx.koin.get() }
    private val keyReadWriteRepository: KeyReadWriteRepository by lazy { ctx.koin.get() }
    private val vaultSessionLocker: VaultSessionLocker by lazy { ctx.koin.get() }
    private val clearVaultSession: ClearVaultSession by lazy { ctx.koin.get() }

    /**
     * Drives the shared [AppWorker]: while it is `>= STARTED` the worker keeps the
     * session's notifications hub (live sync) connected and re-runs the on-start
     * QueueSyncAll. Seeded to RESUMED because the app launches to the foreground
     * and SwiftUI's `onChange(of:scenePhase)` does not fire for the initial value.
     */
    private val lifecycleStateFlow = MutableStateFlow(LeLifecycleState.RESUMED)
    private var refreshBiometricsOnActivation = false

    init {
        if (ctx.runtime == KeyguardRuntime.APP) {
            startAppWorkers()
        }
    }

    private fun startAppWorkers() {
        // The shared sync worker tracks the vault session itself and only runs once
        // unlocked, so no gating is needed here (the same entry point as desktop).
        val appWorker = ctx.koin.get<AppWorker>(qualifier = named(AppWorker.Feature.SYNC))
        appWorker.launch(ctx.backgroundScope, lifecycleStateFlow)

        // Populate the per-account favicon servers so website icon URLs can be
        // resolved. Without it the transform no-ops and no favicon URL is produced.
        startFaviconServers()

        // Write (or clear) the persisted master key, so the vault survives app
        // restarts while "Keep the vault unlocked" is on.
        startVaultPersist()

        // "Auto-lock after": while the app is in the foreground the session is
        // kept alive; once it leaves the foreground the locker schedules a lock.
        startVaultSessionLocker()

        // Mirror the in-app language override into AppleLanguages so the shared
        // string resources resolve to the chosen language on the next launch.
        startLocaleApplier(ctx)

        // Picked-file copies belong to the forms of a previous process;
        // a form that got cancelled never staged (and so never deleted) its copy.
        ctx.backgroundScope.launch {
            AppleManagedImportFiles.clear()
        }
    }

    private var watchtowerStarted = false

    fun startWatchtower() {
        if (ctx.runtime != KeyguardRuntime.APP) return
        if (watchtowerStarted) return
        watchtowerStarted = true
        // AppWorker only starts synchronization. Watchtower is a separate worker
        // which maintains the alert records consumed by the dashboard.
        ctx.koin.get<WatchtowerSyncer>()
            .start(ctx.backgroundScope, lifecycleStateFlow)
    }

    private var backupSchedulerStarted = false

    fun startAutomaticBackups() {
        if (ctx.runtime != KeyguardRuntime.APP) return
        if (backupSchedulerStarted) return
        backupSchedulerStarted = true
        ctx.koin.get<com.artemchep.keyguard.common.service.backup.BackupSchedulerWorker>()
            .start(ctx.backgroundScope, lifecycleStateFlow)
    }

    private fun startFaviconServers() {
        ctx.backgroundScope.launch {
            ctx.unlockUseCase().collectLatest { state ->
                if (state is VaultState.Main) {
                    val getAccounts = state.sessionKoin.get<GetAccounts>()
                    coroutineScope {
                        Favicon.launch(this, getAccounts)
                    }
                }
            }
        }
    }

    private fun startVaultPersist() {
        ctx.backgroundScope.launch {
            combine(
                getVaultSession(),
                getVaultPersist(),
            ) { session, persist ->
                val key = session as? MasterSession.Key
                key?.masterKey?.takeIf { persist }
            }.collect { masterKey ->
                val persistedSession = masterKey?.let {
                    PersistedSession(
                        masterKey = it,
                        createdAt = Clock.System.now(),
                        persistedAt = Clock.System.now(),
                    )
                }
                keyReadWriteRepository.put(persistedSession)
                    .attempt()
                    .bind()
            }
        }
    }

    private fun startVaultSessionLocker() {
        ctx.backgroundScope.launch {
            lifecycleStateFlow
                .onState(minActiveState = LeLifecycleState.RESUMED) {
                    vaultSessionLocker.keepAlive()
                }
                .collect()
        }
    }

    fun setScenePhase(phase: KeyguardScenePhase) {
        if (phase == KeyguardScenePhase.BACKGROUND) refreshBiometricsOnActivation = true
        // A biometric sheet can itself make the scene inactive. Refresh only
        // after backgrounding, or dismissal could restart the in-flight unlock.
        if (phase == KeyguardScenePhase.ACTIVE && refreshBiometricsOnActivation) {
            refreshBiometricsOnActivation = false
            com.artemchep.keyguard.core.session.usecase.AppleBiometricAvailability.refresh()
        }
        lifecycleStateFlow.value = when (phase) {
            KeyguardScenePhase.ACTIVE -> LeLifecycleState.RESUMED
            KeyguardScenePhase.INACTIVE -> LeLifecycleState.STARTED
            KeyguardScenePhase.BACKGROUND -> LeLifecycleState.CREATED
        }
    }

    /** Terminal teardown for an extension request, completed before returning credentials. */
    suspend fun closeAutofillSession() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        setScenePhase(KeyguardScenePhase.BACKGROUND)
        val job = ctx.scope.coroutineContext[kotlinx.coroutines.Job]
        job?.cancel()
        job?.join()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            clearVaultSession(LockReason.LOCK, TextHolder.Res(Res.string.lock_reason_manually)).bind()
        }
    }

    fun observeStatus(
        onChange: (KeyguardVaultStatus) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            ctx.unlockUseCase()
                .map { it.toStatus() }
                .collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    fun lockVault() {
        val reason = TextHolder.Res(Res.string.lock_reason_manually)
        clearVaultSession(LockReason.LOCK, reason)
            .launchIn(ctx.scope)
    }

    /** Queues a sync of every account; the sync worker reports progress through the observed sync status. */
    fun syncVault() {
        ctx.scope.launch {
            // Sync dependencies belong to the unlocked session, not the app DI.
            // Resolve on every invocation so a relock never retains the old vault.
            val state = ctx.currentState() as? VaultState.Main ?: return@launch
            val queueSyncAll = state.sessionKoin.get<QueueSyncAll>()
            queueSyncAll().bind()
        }
    }
}

private fun startLocaleApplier(ctx: CoreContext) {
    // Serialize with AppLocalization's MainActor preference updates: a
    // background write must not restore an old override during system reset.
    ctx.scope.launch {
        val getLocale: GetLocale = ctx.koin.get()
        getLocale().collect { locale ->
            val defaults = NSUserDefaults.standardUserDefaults
            if (locale == null) {
                defaults.removeObjectForKey("AppleLanguages")
            } else {
                defaults.setObject(listOf(locale), forKey = "AppleLanguages")
            }
        }
    }
}
