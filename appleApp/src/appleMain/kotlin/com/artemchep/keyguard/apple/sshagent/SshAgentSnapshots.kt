package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot

/** A pending per-sign approval request shown by the SSH approval window. */
data class SshAgentRequestSnapshot(
    val id: String,
    val keyName: String,
    val keyFingerprint: String,
    val callerName: String,
    val callerPath: String,
    /** Total approval window in ms — the countdown denominator. */
    val timeoutMs: Long,
    val expiresAtEpochMs: Long,
)

enum class SshAgentRunState {
    UNSUPPORTED,
    STOPPED,
    STARTING,
    READY,
    FAILED,
}

data class SshAgentStatusSnapshot(
    val running: Boolean,
    val enabled: Boolean,
    val state: SshAgentRunState,
    val sshAuthSock: String?,
) {
    companion object {
        val empty = SshAgentStatusSnapshot(
            running = false,
            enabled = false,
            state = SshAgentRunState.STOPPED,
            sshAuthSock = null,
        )
    }
}

/** The approval-window picker follows the duration-picker convention: option id = whole milliseconds as a string. */
data class SshAgentSettingsSnapshot(
    val loaded: Boolean,
    val enabled: Boolean,
    val approvalWindowTitle: String,
    val approvalWindowOptions: List<SettingOptionSnapshot>,
    val displayKeyNames: Boolean,
    val filterActive: Boolean,
) {
    companion object {
        val empty = SshAgentSettingsSnapshot(
            loaded = false,
            enabled = false,
            approvalWindowTitle = "",
            approvalWindowOptions = emptyList(),
            displayKeyNames = false,
            filterActive = false,
        )
    }
}

