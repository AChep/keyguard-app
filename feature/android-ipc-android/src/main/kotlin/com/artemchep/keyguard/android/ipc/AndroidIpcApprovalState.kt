package com.artemchep.keyguard.android.ipc

import org.jetbrains.compose.resources.StringResource

internal sealed interface AndroidIpcApprovalState {
    data object Loading : AndroidIpcApprovalState

    data object Unavailable : AndroidIpcApprovalState

    data class Ready(
        val appLabel: String,
        val packageName: String,
        val protocolLabel: StringResource,
        val operation: StringResource,
        val candidates: List<AndroidIpcApprovalCoordinator.Candidate>,
        val selectedKeyIds: Set<String>,
        val allowMultiple: Boolean,
        val allowEmpty: Boolean,
        val registerApp: Boolean,
        val requiresAuthentication: Boolean,
        val onSelect: (String) -> Unit,
        val onApprove: () -> Unit,
        val onDeny: () -> Unit,
    ) : AndroidIpcApprovalState
}
