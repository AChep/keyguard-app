package com.artemchep.keyguard.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.artemchep.bindin.bindBlock
import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.service.power.PowerService
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.resolve
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.toCommon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.lock_reason_screen_off
import com.artemchep.keyguard.util.foundation.crypto.ensurePlatformCryptoReady
import kotlin.getValue
import kotlin.time.Clock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named

abstract class BaseApp : Application(), KeyguardKoinOwner {
    abstract val koinApplication: org.koin.core.KoinApplication

    final override val koin: org.koin.core.Koin
        get() = koinApplication.koin

    companion object {
        var context: Context? = null
    }

    init {
        ensurePlatformCryptoReady()
    }

    override fun attachBaseContext(base: Context) {
        context = base
        super.attachBaseContext(base)
    }
}

fun <T> T.installWorkers() where T : BaseApp, T : KeyguardKoinOwner {
    val processLifecycleOwner = ProcessLifecycleOwner.get()
    val processLifecycle = processLifecycleOwner.lifecycle
    val processLifecycleFlow = processLifecycle
        .currentStateFlow
        // Convert to platform-agnostic
        // lifecycle state.
        .map { state ->
            state.toCommon()
        }

    // App worker
    val appWorker: AppWorker by lazy { koin.get(qualifier = named(AppWorker.Feature.SYNC)) }
    processLifecycleOwner.lifecycleScope.launch {
        appWorker.launch(this, processLifecycleFlow)
    }

    // All workers
    val workers = koin.get<WorkerRegistry>().values
    workers.forEach {
        ProcessLifecycleOwner.get().bindBlock {
            coroutineScope {
                it.start(this, processLifecycleFlow)
            }
        }
    }
}

fun <T> T.installFavicons() where T : BaseApp, T : KeyguardKoinOwner {
    val getVaultSession: GetVaultSession by lazy { koin.get() }

    // favicon
    ProcessLifecycleOwner.get().bindBlock {
        getVaultSession()
            .map { session ->
                val key = session as? MasterSession.Key
                key?.session?.resolve { get<GetAccounts>() }
            }
            .collectLatest { getAccounts ->
                if (getAccounts != null) {
                    coroutineScope {
                        Favicon.launch(this, getAccounts)
                    }
                }
            }
    }
}

fun <T> T.installVaultLock() where T : BaseApp, T : KeyguardKoinOwner {
    val getVaultSession: GetVaultSession by lazy { koin.get() }
    val clearVaultSession: ClearVaultSession by lazy { koin.get() }

    // screen lock
    val getVaultLockAfterScreenOff: GetVaultLockAfterScreenOff by lazy { koin.get() }
    val powerService: PowerService by lazy { koin.get() }
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
}

fun <T> T.installVaultKeepAlive() where T : BaseApp, T : KeyguardKoinOwner {
    val vaultSessionLocker: VaultSessionLocker by lazy { koin.get() }
    ProcessLifecycleOwner.get().bindBlock {
        vaultSessionLocker.keepAlive()
    }
}

fun <T> T.installVaultPersistedSession() where T : BaseApp, T : KeyguardKoinOwner {
    val getVaultSession: GetVaultSession by lazy { koin.get() }
    val getVaultPersist: GetVaultPersist by lazy { koin.get() }
    val keyReadWriteRepository: KeyReadWriteRepository by lazy { koin.get() }

    val processLifecycleOwner = ProcessLifecycleOwner.get()

    // persist
    processLifecycleOwner.bindBlock {
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
}
