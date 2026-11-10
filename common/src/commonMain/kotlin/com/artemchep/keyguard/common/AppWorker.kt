package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agenthistory.AgentHistoryRetentionWorker
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverRefreshWorker
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseSyncer
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.service.session.AppWorkerSessionAccess
import com.artemchep.keyguard.common.service.session.AppWorkerSessionDependencies
import com.artemchep.keyguard.common.service.session.PendingUsageHistorySessionAccess
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.urlblock.UrlBlockExposedSyncer
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkWorker
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

class AppWorkerIm(
    private val getVaultSession: GetVaultSession,
    private val appWorkerSessionAccess: AppWorkerSessionAccess,
    private val pendingUsageHistorySessionAccess: PendingUsageHistorySessionAccess,
    private val updateVersionLog: UpdateVersionLog,
    private val temporaryArtifactMaintenance: TemporaryArtifactMaintenance,
    private val pendingUsageHistoryEnabled: Boolean,
) : AppWorker {
    companion object {
        private const val FILE_CLEANUP_DELAY_MS = 15_000L
    }

    override fun launch(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ): Job = scope.launch {
        // The app should maintain the sync status
        // while it is visible to a user.
        flow
            .onState(LeLifecycleState.STARTED) {
                launchWhenAvailable(this, { it.notificationsWorker }, NotificationsWorker::launch)
                launchWhenAvailable(this, { it.exposedAccountSyncer }, ExposedAccountSyncer::launch)
                launchWhenAvailable(this, { it.sshAgentPublicKeySyncer }, SshAgentPublicKeySyncer::launch)
                launchWhenAvailable(this, { it.urlBlockExposedSyncer }, UrlBlockExposedSyncer::launch)
                launchWhenAvailable(this, { it.gpgPublicKeySyncer }, GpgPublicKeySyncer::launch)
                launchWhenAvailable(this, { it.gpgKeyserverRefreshWorker }, GpgKeyserverRefreshWorker::launch)
                launchWhenAvailable(this, { it.agentHistoryRetentionWorker }, AgentHistoryRetentionWorker::launch)
                launchWhenAvailable(this, { it.licenseSyncer }, LicenseSyncer::launch)
                launchWhenAvailable(this, { it.appLinkWorker }, AppLinkWorker::launch)

                launchPendingUsageHistoryFlushWhenAvailable(
                    scope = this,
                    getVaultSession = getVaultSession,
                    sessionAccess = pendingUsageHistorySessionAccess,
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

    /**
     * Launches the worker that [select] picks from the dependencies of the vault session
     * while the vault is unlocked, until the worker changes.
     */
    private fun <T : Any> launchWhenAvailable(
        scope: CoroutineScope,
        select: (AppWorkerSessionDependencies) -> T,
        start: (T, CoroutineScope) -> Job,
    ) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.let(appWorkerSessionAccess::invoke)?.let(select)
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { worker ->
            if (worker == null) {
                return@mapLatest
            }

            // Launch the worker forever until the
            // worker changes.
            coroutineScope {
                start(worker, this)
            }
        }
        .launchIn(scope)
}

internal fun launchPendingUsageHistoryFlushWhenAvailable(
    scope: CoroutineScope,
    getVaultSession: GetVaultSession,
    sessionAccess: PendingUsageHistorySessionAccess,
    enabled: Boolean,
): Job? {
    if (!enabled) {
        return null
    }
    return getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.let(sessionAccess::invoke)
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
