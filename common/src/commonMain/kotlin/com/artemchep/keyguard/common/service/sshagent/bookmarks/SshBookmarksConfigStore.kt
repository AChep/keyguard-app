package com.artemchep.keyguard.common.service.sshagent.bookmarks

import kotlinx.coroutines.flow.StateFlow

/**
 * The directory of the SSH configuration generated from SSH bookmarks.
 * Only platforms whose SSH clients can read it provide one.
 */
interface SshBookmarksConfigStore {
    /** The configuration file, as an absolute path for an `Include` line. */
    val includePath: String

    /** The directory, as the configuration refers to it, for example `~/.ssh/keyguard`. */
    val configDirectory: String

    /** What the directory holds since the last sync, or `null` if unknown. */
    val summary: StateFlow<SshBookmarksSummary?>

    /** Whether `~/.ssh/config` includes the configuration, or `null` if the app can't read it. */
    suspend fun isIncluded(): Boolean?

    /** Writes the [config], then removes the files it no longer has. */
    suspend fun replaceAll(config: SshBookmarksConfig)

    /** Removes the generated files, and the directory if nothing else is left. */
    suspend fun clear()
}

data class SshBookmarksSummary(
    /** The number of `Match` blocks: hosts, with or without a user. */
    val hosts: Int,
    /** The number of bookmarks left out of the configuration. */
    val skipped: Int,
)
