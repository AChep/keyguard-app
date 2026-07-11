package com.artemchep.keyguard.android.sshagent

object SshAgentContract {
    const val ACTION_RUN_ANDROID_SSH_AGENT = "com.artemchep.keyguard.action.RUN_ANDROID_SSH_AGENT"

    // Keep aligned with androidSshAgent/src/src/daemon.rs.
    const val MAX_CONCURRENT_SESSIONS = 8

    private const val BROADCAST_RESULT_PREFIX = "keyguard.ssh-agent.broadcast.v1:"

    //
    // extra
    //

    const val EXTRA_PROTOCOL_VERSION = "com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION"
    const val EXTRA_PROXY_PORT = "com.artemchep.keyguard.extra.SSH_AGENT_PROXY_PORT"
    const val EXTRA_SESSION_ID = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID"
    const val EXTRA_SESSION_SECRET = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET"

    internal enum class BroadcastOutcome(
        val wireValue: String,
        val accepted: Boolean,
    ) {
        ACCEPTED(
            wireValue = BROADCAST_RESULT_PREFIX + "accepted",
            accepted = true,
        ),
        DEFERRED(
            wireValue = BROADCAST_RESULT_PREFIX + "deferred",
            accepted = true,
        ),
        BUSY(
            wireValue = BROADCAST_RESULT_PREFIX + "busy",
            accepted = false,
        ),
        DISABLED(
            wireValue = BROADCAST_RESULT_PREFIX + "disabled",
            accepted = false,
        ),
        INVALID(
            wireValue = BROADCAST_RESULT_PREFIX + "invalid",
            accepted = false,
        ),
        START_FAILED(
            wireValue = BROADCAST_RESULT_PREFIX + "start_failed",
            accepted = false,
        ),
        INTERNAL_ERROR(
            wireValue = BROADCAST_RESULT_PREFIX + "internal_error",
            accepted = false,
        ),
    }
}
