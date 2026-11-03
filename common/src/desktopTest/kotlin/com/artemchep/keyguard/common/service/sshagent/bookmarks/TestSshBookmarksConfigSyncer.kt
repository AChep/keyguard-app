package com.artemchep.keyguard.common.service.sshagent.bookmarks

import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.passkey.FakeVaultSessions
import com.artemchep.keyguard.common.service.session.SshBookmarksSessionAccess
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentBookmarksConfig
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A syncer that writes to the [store], with the [bookmarks] of every unlocked vault. */
internal fun testSshBookmarksConfigSyncer(
    store: SshBookmarksConfigStore,
    getVaultSession: GetVaultSession = FakeVaultSessions(),
    sshAgent: Flow<Boolean> = flowOf(true),
    enabled: Flow<Boolean> = flowOf(true),
    bookmarks: Flow<SshBookmarks>? = null,
    socketPath: suspend () -> String? = { "/tmp/agent.sock" },
): SshBookmarksConfigSyncer {
    val source = bookmarks?.let {
        object : SshBookmarksSource {
            override fun get(): Flow<SshBookmarks> = it
        }
    }
    return SshBookmarksConfigSyncer(
        getVaultSession = getVaultSession,
        getSshAgent = object : GetSshAgent {
            override fun invoke(): Flow<Boolean> = sshAgent
        },
        getSshAgentBookmarksConfig = object : GetSshAgentBookmarksConfig {
            override fun invoke(): Flow<Boolean> = enabled
        },
        sshBookmarksSessionAccess = SshBookmarksSessionAccess { source },
        sshBookmarksConfigStore = store,
        sshAgentSocketPathProvider = { socketPath() },
        logRepository = LogRepositoryBridge(emptyList()),
    )
}
