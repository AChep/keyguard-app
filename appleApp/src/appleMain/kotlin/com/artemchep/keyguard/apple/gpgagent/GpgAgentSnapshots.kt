package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot

enum class GpgAgentRunState {
    UNSUPPORTED, STOPPED, STARTING, READY, FAILED,
}

enum class GpgAgentOperationSnapshot {
    SIGN, DECRYPT,
}

data class GpgAgentStatusSnapshot(
    val running: Boolean,
    val enabled: Boolean,
    val state: GpgAgentRunState,
    val gpgHome: String?,
    val agentSocket: String?,
    val setupCommand: String?,
    val diagnostic: String?,
) {
    companion object {
        val empty = GpgAgentStatusSnapshot(false, false, GpgAgentRunState.STOPPED, null, null, null, null)
    }
}

data class GpgAgentRequestSnapshot(
    val id: String,
    val keyName: String,
    val keyFingerprint: String,
    val callerName: String,
    val callerPath: String,
    val operation: GpgAgentOperationSnapshot,
    val timeoutMs: Long,
    val expiresAtEpochMs: Long,
)

data class GpgAgentSettingsSnapshot(
    val loaded: Boolean,
    val enabled: Boolean,
    val approvalWindowTitle: String,
    val approvalWindowOptions: List<SettingOptionSnapshot>,
    val approvalCachePolicyTitle: String,
    val approvalCachePolicyOptions: List<SettingOptionSnapshot>,
    val displayKeyNames: Boolean,
    val filterActive: Boolean,
) {
    companion object {
        val empty = GpgAgentSettingsSnapshot(false, false, "", emptyList(), "", emptyList(), false, false)
    }
}

data class GpgAgentFiltersSnapshot(
    val loaded: Boolean,
    val count: Int,
    val items: List<VaultFilterItemSnapshot>,
    val canSave: Boolean,
    val canReset: Boolean,
) {
    companion object {
        val empty = GpgAgentFiltersSnapshot(false, 0, emptyList(), false, false)
    }
}

enum class GpgAgentHistoryItemKind {
    SECTION, VALUE,
}

data class GpgAgentHistoryItemSnapshot(
    val id: String,
    val kind: GpgAgentHistoryItemKind,
    val caller: String,
    val description: String,
    val date: String?,
    val responseText: String,
    val request: String?,
    val response: String?,
)

data class GpgAgentHistorySnapshot(
    val loaded: Boolean,
    val subtitle: String?,
    val items: List<GpgAgentHistoryItemSnapshot>,
    val canClear: Boolean,
) {
    companion object {
        val empty = GpgAgentHistorySnapshot(false, null, emptyList(), false)
    }
}
