package com.artemchep.keyguard.common.service.sshagent.bookmarks

import kotlinx.coroutines.flow.Flow

/** The bookmarks of the unlocked vault. */
interface SshBookmarksSource {
    fun get(): Flow<SshBookmarks>
}

data class SshBookmarks(
    /** The bookmarks, each with the key that the agent offers for it. */
    val entries: List<SshBookmarkEntry>,
    /** The number of bookmarks without such a key, or that can't be pinned. */
    val skipped: Int,
)
