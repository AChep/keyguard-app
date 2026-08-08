package com.artemchep.keyguard.android.ipc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun produceAndroidIpcApprovalState(
    requestId: String,
    onApprove: (Set<String>) -> Unit,
    onDeny: () -> Unit,
): AndroidIpcApprovalState {
    var snapshot by remember(requestId) {
        mutableStateOf<AndroidIpcApprovalCoordinator.Snapshot?>(null)
    }
    var loaded by remember(requestId) {
        mutableStateOf(false)
    }
    LaunchedEffect(requestId) {
        snapshot = AndroidIpcApprovalCoordinator.snapshot(requestId)
        loaded = true
    }
    val current = snapshot
        ?: return if (loaded) {
            AndroidIpcApprovalState.Unavailable
        } else {
            AndroidIpcApprovalState.Loading
        }
    var selectedKeyIds by remember(current.id) {
        mutableStateOf(
            current.candidates
                .singleOrNull()
                ?.let { setOf(it.id) }
                .orEmpty(),
        )
    }
    return AndroidIpcApprovalState.Ready(
        appLabel = current.appLabel,
        packageName = current.packageName,
        protocolLabel = current.protocolLabel,
        operation = current.operation,
        candidates = current.candidates,
        selectedKeyIds = selectedKeyIds,
        allowMultiple = current.allowMultiple,
        allowEmpty = current.allowEmpty,
        registerApp = current.registerApp,
        requiresAuthentication = current.requiresAuthentication,
        onSelect = { id ->
            selectedKeyIds = if (current.allowMultiple) {
                if (id in selectedKeyIds) {
                    selectedKeyIds - id
                } else {
                    selectedKeyIds + id
                }
            } else {
                setOf(id)
            }
        },
        onApprove = {
            if (selectedKeyIds.isNotEmpty() || current.allowEmpty) {
                onApprove(selectedKeyIds)
            }
        },
        onDeny = onDeny,
    )
}
