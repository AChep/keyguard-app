package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverRefreshWorker
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseSyncer
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.platform.util.hasWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

class AppWorkerIm(
    private val getVaultSession: GetVaultSession,
    private val updateVersionLog: UpdateVersionLog,
    private val temporaryArtifactMaintenance: TemporaryArtifactMaintenance,
    private val pendingUsageHistoryEnabled: Boolean,
) : AppWorker {
    companion object {
        private const val FILE_CLEANUP_DELAY_MS = 15_000L
    }

    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
        updateVersionLog = directDI.instance(),
        temporaryArtifactMaintenance = directDI.instance(),
        pendingUsageHistoryEnabled = shouldLaunchPendingUsageHistoryFlush(CurrentPlatform),
    )

    override fun launch(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ): Job = scope.launch {
        // The app should maintain the sync status
        // while it is visible to a user.
        flow
            .onState(LeLifecycleState.STARTED) {
                launchSyncManagerWhenAvailable(this)
                launchSyncExposedAccountsWhenAvailable(this)
                launchSyncSshAgentWhenAvailable(this)
                launchSyncGpgAgentWhenAvailable(this)
                launchRefreshGpgKeyserverWhenAvailable(this)
                launchSyncLicenseWhenAvailable(this)

                launchPendingUsageHistoryFlushWhenAvailable(
                    scope = this,
                    getVaultSession = getVaultSession,
                    enabled = pendingUsageHistoryEnabled,
                )
            }
            .launchIn(this)
        // The app should keep a log of last installed versions,
        // so we can show a nice changelog to a user.
        flow
            .onState(LeLifecycleState.STARTED) {
                updateVersionLog()
                    .attempt()
                    .launchIn(this)
            }
            .take(1) // no need to restart, the version won't change
            .launchIn(this)
        // A killed process or power loss can leave staged temporary
        // artifacts behind; no transaction can clean up after itself in
        // those cases, so the app sweeps its storage roots once per launch.
        flow
            .onState(LeLifecycleState.STARTED) {
                launch {
                    // Let the app load fully and do not compete for the
                    // resources.
                    delay(FILE_CLEANUP_DELAY_MS)
                    temporaryArtifactMaintenance()
                }
            }
            .take(1)
            .launchIn(this)
    }

    private fun launchSyncManagerWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<NotificationsWorker>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { syncManager ->
            if (syncManager == null) {
                return@mapLatest
            }

            // Launch the sync manager forever until the
            // sync manager changes.
            coroutineScope {
                syncManager.launch(this)
            }
        }
        .launchIn(scope)

    private fun launchSyncExposedAccountsWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<ExposedAccountSyncer>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { syncManager ->
            if (syncManager == null) {
                return@mapLatest
            }

            // Launch the sync manager forever until the
            // sync manager changes.
            coroutineScope {
                syncManager.launch(this)
            }
        }
        .launchIn(scope)

    private fun launchSyncSshAgentWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<SshAgentPublicKeySyncer>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { syncManager ->
            if (syncManager == null) {
                return@mapLatest
            }

            // Launch the sync manager forever until the
            // sync manager changes.
            coroutineScope {
                syncManager.launch(this)
            }
        }
        .launchIn(scope)

    private fun launchSyncGpgAgentWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<GpgPublicKeySyncer>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { syncManager ->
            if (syncManager == null) {
                return@mapLatest
            }

            // Launch the sync manager forever until the
            // sync manager changes.
            coroutineScope {
                syncManager.launch(this)
            }
        }
        .launchIn(scope)

    private fun launchRefreshGpgKeyserverWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<GpgKeyserverRefreshWorker>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { worker ->
            if (worker == null) {
                return@mapLatest
            }

            // Launch the worker forever until the
            // worker changes.
            coroutineScope {
                worker.launch(this)
            }
        }
        .launchIn(scope)

    private fun launchSyncLicenseWhenAvailable(
        scope: CoroutineScope,
    ) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<LicenseSyncer>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { worker ->
            if (worker == null) {
                return@mapLatest
            }

            coroutineScope {
                worker.launch(this)
            }
        }
        .launchIn(scope)
}

internal fun launchPendingUsageHistoryFlushWhenAvailable(
    scope: CoroutineScope,
    getVaultSession: GetVaultSession,
    enabled: Boolean,
): Job? {
    if (!enabled) {
        return null
    }
    return getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<PendingUsageHistoryFlushRunner>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { runner ->
            runner?.run()
                ?.attempt()
                ?.bind()
        }
        .launchIn(scope)
}

internal fun shouldLaunchPendingUsageHistoryFlush(
    platform: Platform,
): Boolean = !platform.hasWatch()

interface AppWorker {
    enum class Feature {
        SYNC,
    }

    fun launch(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ): Job
}
