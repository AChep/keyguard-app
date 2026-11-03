package com.artemchep.keyguard.common.service.sshagent.bookmarks

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.session.SshBookmarksSessionAccess
import com.artemchep.keyguard.common.service.sshagent.SshAgentSocketPathProvider
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentBookmarksConfig
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The only writer of the generated configuration, so that a late removal
 * never undoes a write. It writes the configuration while the agent and the
 * setting are on and the vault is unlocked, keeps it while the vault is
 * locked, as the agent still offers the keys, and removes it once the agent
 * or the setting is off: it would pin hosts to keys that nothing offers.
 */
class SshBookmarksConfigSyncer(
    private val getVaultSession: GetVaultSession,
    private val getSshAgent: GetSshAgent,
    private val getSshAgentBookmarksConfig: GetSshAgentBookmarksConfig,
    private val sshBookmarksSessionAccess: SshBookmarksSessionAccess,
    private val sshBookmarksConfigStore: SshBookmarksConfigStore,
    private val sshAgentSocketPathProvider: SshAgentSocketPathProvider,
    private val logRepository: LogRepository,
) : Wrker {
    companion object {
        private const val TAG = "SshBookmarksConfigSyncer"

        private val SOCKET_RETRY_DELAY_MIN = 30.seconds
        private val SOCKET_RETRY_DELAY_MAX = 1.hours
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        val syncStateFlow = combine(
            getSshAgent(),
            getSshAgentBookmarksConfig(),
        ) { sshAgent, bookmarksConfig ->
            sshAgent && bookmarksConfig
        }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    return@flatMapLatest flowOf(SyncState.Disabled)
                }

                combine(
                    bookmarksFlow(),
                    socketPathFlow(),
                ) { bookmarks, socketPath ->
                    SyncState.Enabled(
                        bookmarks = bookmarks,
                        socketPath = socketPath,
                    )
                }
            }
            .distinctUntilChanged()
        scope.launch {
            // The workers share a scope, so a failure
            // here must not cancel the other workers.
            runCatchingNonFatal {
                syncStateFlow.collectLatest(::sync)
            }.onFailure { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Stopped syncing the SSH configuration of bookmarked hosts: ${e.message}",
                    level = LogLevel.ERROR,
                )
            }
        }
    }

    /** The bookmarks of the unlocked vault; nothing while it is locked. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun bookmarksFlow(): Flow<SshBookmarks> = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.let(sshBookmarksSessionAccess::invoke)
        }
        .distinctUntilChanged { old, new -> old === new }
        .flatMapLatest { source ->
            source?.get() ?: emptyFlow()
        }

    /**
     * The socket of the agent, or `null` until it resolves. Retries with
     * a growing delay, so that the configuration gets it once it does.
     */
    private fun socketPathFlow(): Flow<String?> = flow {
        val backoff = ReconnectBackoff(
            baseDelayMs = SOCKET_RETRY_DELAY_MIN.inWholeMilliseconds,
            maxDelayMs = SOCKET_RETRY_DELAY_MAX.inWholeMilliseconds,
            jitterRatio = 0.0,
        )
        while (true) {
            val socketPath = sshAgentSocketPathProvider()
            emit(socketPath)
            if (socketPath != null) {
                break
            }
            delay(backoff.nextDelayMs())
        }
    }

    private suspend fun sync(state: SyncState) {
        runCatchingNonFatal {
            when (state) {
                SyncState.Disabled -> sshBookmarksConfigStore.clear()
                is SyncState.Enabled -> {
                    val config = renderSshBookmarksConfig(
                        entries = state.bookmarks.entries,
                        skipped = state.bookmarks.skipped,
                        directory = sshBookmarksConfigStore.configDirectory,
                        identityAgent = state.socketPath,
                    )
                    sshBookmarksConfigStore.replaceAll(config)
                }
            }
        }.onFailure { e ->
            logRepository.post(
                tag = TAG,
                message = "Failed to sync the SSH configuration of bookmarked hosts: ${e.message}",
                level = LogLevel.ERROR,
            )
        }
    }

    private sealed interface SyncState {
        data object Disabled : SyncState

        data class Enabled(
            val bookmarks: SshBookmarks,
            val socketPath: String?,
        ) : SyncState
    }
}
