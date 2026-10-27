package com.artemchep.keyguard.android.agentbridge

import com.artemchep.keyguard.common.R

/**
 * An agent that a Termux helper bridges to the app. Each one has its own
 * receiver intent, so a session never reaches the other protocol's handler.
 *
 * Keep aligned with the `BridgeContract` of each helper crate.
 */
enum class AgentBridge(
    val action: String,
    val extraProtocolVersion: String,
    val extraProxyPort: String,
    val extraSessionId: String,
    val extraSessionSecret: String,
    internal val broadcastResultPrefix: String,
    internal val text: AgentBridgeText,
) {
    SSH(
        action = "com.artemchep.keyguard.action.RUN_ANDROID_SSH_AGENT",
        extraProtocolVersion = "com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION",
        extraProxyPort = "com.artemchep.keyguard.extra.SSH_AGENT_PROXY_PORT",
        extraSessionId = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID",
        extraSessionSecret = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET",
        broadcastResultPrefix = "keyguard.ssh-agent.broadcast.v1:",
        text = AgentBridgeText(
            requestChannelId = R.string.notification_termux_ssh_request_channel_id,
            requestChannelName = R.string.notification_termux_ssh_request_channel_name,
            title = R.string.notification_termux_ssh_title,
            connecting = R.string.notification_termux_ssh_content,
            error = R.string.notification_termux_ssh_error,
            disabled = R.string.notification_termux_ssh_request_rejected_title,
            request = R.string.notification_termux_ssh_content_sign,
            requestApp = R.string.notification_termux_ssh_content_sign_app,
        ),
    ),
    GPG(
        action = "com.artemchep.keyguard.action.RUN_ANDROID_GPG_AGENT",
        extraProtocolVersion = "com.artemchep.keyguard.extra.GPG_AGENT_PROTOCOL_VERSION",
        extraProxyPort = "com.artemchep.keyguard.extra.GPG_AGENT_PROXY_PORT",
        extraSessionId = "com.artemchep.keyguard.extra.GPG_AGENT_SESSION_ID",
        extraSessionSecret = "com.artemchep.keyguard.extra.GPG_AGENT_SESSION_SECRET",
        broadcastResultPrefix = "keyguard.gpg-agent.broadcast.v1:",
        text = AgentBridgeText(
            requestChannelId = R.string.notification_termux_gpg_request_channel_id,
            requestChannelName = R.string.notification_termux_gpg_request_channel_name,
            title = R.string.notification_termux_gpg_title,
            connecting = R.string.notification_termux_gpg_content,
            error = R.string.notification_termux_gpg_error,
            disabled = R.string.notification_termux_gpg_request_rejected_title,
            request = R.string.notification_termux_gpg_content_request,
            requestApp = R.string.notification_termux_gpg_content_request_app,
        ),
    ),
    ;

    companion object {
        fun ofAction(action: String?): AgentBridge? = entries.firstOrNull { it.action == action }
    }
}

object AgentBridgeContract {
    // Keep aligned with MAX_CONCURRENT_SESSIONS in commonAgent/android-bridge.
    const val MAX_CONCURRENT_SESSIONS = 8

    internal enum class BroadcastOutcome(
        private val wireName: String,
        val accepted: Boolean,
    ) {
        ACCEPTED(
            wireName = "accepted",
            accepted = true,
        ),
        DEFERRED(
            wireName = "deferred",
            accepted = true,
        ),
        BUSY(
            wireName = "busy",
            accepted = false,
        ),
        DISABLED(
            wireName = "disabled",
            accepted = false,
        ),
        INVALID(
            wireName = "invalid",
            accepted = false,
        ),
        START_FAILED(
            wireName = "start_failed",
            accepted = false,
        ),
        INTERNAL_ERROR(
            wireName = "internal_error",
            accepted = false,
        ),
        ;

        fun wireValue(bridge: AgentBridge): String = bridge.broadcastResultPrefix + wireName
    }
}
