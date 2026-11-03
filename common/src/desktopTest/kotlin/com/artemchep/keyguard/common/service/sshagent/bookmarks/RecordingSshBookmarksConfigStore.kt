package com.artemchep.keyguard.common.service.sshagent.bookmarks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the last written configuration in memory; fails the first [failures] writes. */
internal class RecordingSshBookmarksConfigStore(
    private var failures: Int = 0,
) : SshBookmarksConfigStore {
    var config: SshBookmarksConfig? = null

    var clears = 0
        private set

    override val includePath: String = "/home/me/.ssh/keyguard/config"

    override val configDirectory: String = "~/.ssh/keyguard"

    override val summary: StateFlow<SshBookmarksSummary?> = MutableStateFlow(null)

    override suspend fun isIncluded(): Boolean? = null

    override suspend fun replaceAll(config: SshBookmarksConfig) {
        if (failures > 0) {
            failures -= 1
            error("Disk is full")
        }
        this.config = config
    }

    override suspend fun clear() {
        clears += 1
        config = null
    }

    private fun text(): String? = config
        ?.files
        ?.getValue(SSH_BOOKMARKS_CONFIG_FILE_NAME)

    fun hosts(): List<String> = text()
        ?.lines()
        ?.filter { it.startsWith("Match host ") }
        ?.map { it.removePrefix("Match host ") }
        .orEmpty()

    fun hasIdentityAgent(): Boolean = text()
        ?.lines()
        ?.any { it.trim().startsWith("IdentityAgent ") } == true
}
