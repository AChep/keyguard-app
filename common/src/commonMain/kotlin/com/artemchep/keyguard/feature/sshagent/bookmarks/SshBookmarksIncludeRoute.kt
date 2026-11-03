package com.artemchep.keyguard.feature.sshagent.bookmarks

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRoute

/**
 * Explains how to include the generated SSH configuration of
 * bookmarked hosts, which the setting requires before it turns on.
 */
data class SshBookmarksIncludeRoute(
    /** The `Include` line to add to `~/.ssh/config`. */
    val include: String,
) : DialogRoute {
    @Composable
    override fun Content() {
        SshBookmarksIncludeScreen(
            include = include,
        )
    }
}
