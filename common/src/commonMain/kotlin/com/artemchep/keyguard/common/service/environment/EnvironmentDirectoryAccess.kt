package com.artemchep.keyguard.common.service.environment

/**
 * Gives the agent access to the directories of the local `.env` files it
 * serves. A sandboxed agent, as on macOS, opens each directory with a
 * bookmark that the app hands over.
 */
fun interface EnvironmentDirectoryAccess {
    /**
     * Grants access to the directories of the [mounts] that the agent
     * serves, and ends the access to any other. Returns the mounts the
     * agent may serve, each with the bookmark that it opens the directory
     * with, `null` to open it by its path. A mount whose directory is
     * unavailable is left out.
     */
    suspend fun grant(mounts: List<EnvironmentMount>): List<Pair<EnvironmentMount, ByteArray?>>

    companion object {
        /** For an agent that is not sandboxed: it opens every directory by its path. */
        val ByPath = EnvironmentDirectoryAccess { mounts -> mounts.map { it to null } }
    }
}
