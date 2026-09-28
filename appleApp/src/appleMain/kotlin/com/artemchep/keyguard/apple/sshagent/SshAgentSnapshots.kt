package com.artemchep.keyguard.apple.sshagent

import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.res.*
import kotlin.time.Duration.Companion.milliseconds

/** A pending per-sign approval request shown by the SSH approval window. */
data class SshAgentRequestSnapshot(
    val id: String,
    val keyName: String,
    val keyFingerprint: String,
    val callerName: String,
    val callerPath: String,
    /** Total approval window in ms — the countdown denominator. */
    val timeoutMs: Long,
    /** Absolute deadline as epoch millis; the UI counts down to this. */
    val expiresAtEpochMs: Long,
)

/** The SSH agent's running status + advertised SSH_AUTH_SOCK path. */
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

/**
 * The SSH agent preferences, built from the shared Get/Put use cases by
 * [KeyguardCore.observeSshAgentSettings]. The approval-window picker follows
 * the duration-picker convention: option id = whole milliseconds as a string.
 */
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

/**
 * A flat projection of the SSH agent filters screen, built by
 * [KeyguardCore.observeSshAgentFilters] from the shared producer. Items reuse
 * the vault [VaultFilterItemSnapshot] projection; toggling goes through
 * [KeyguardCore.invokeSshAgentFilter].
 */
data class SshAgentFiltersSnapshot(
    val loaded: Boolean,
    val count: Int,
    val items: List<VaultFilterItemSnapshot>,
    val canSave: Boolean,
    val canReset: Boolean,
) {
    companion object {
        val empty = SshAgentFiltersSnapshot(
            loaded = false,
            count = 0,
            items = emptyList(),
            canSave = false,
            canReset = false,
        )
    }
}
