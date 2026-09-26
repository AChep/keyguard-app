package com.artemchep.keyguard.android.ipc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.feature.android.ipc.presentation.AndroidIpcApprovalCandidate
import com.artemchep.keyguard.feature.android.ipc.presentation.AndroidIpcApprovalPresentationState
import com.artemchep.keyguard.feature.android.ipc.presentation.AndroidIpcApprovalSnapshot
import com.artemchep.keyguard.feature.android.ipc.presentation.AndroidIpcApprovalStateProducer
import com.artemchep.keyguard.feature.android.ipc.presentation.initialAndroidIpcApprovalSelection as initialPresentationSelection

@Composable
internal fun produceAndroidIpcApprovalState(
    requestId: String,
    onApprove: (Set<String>) -> Unit,
    onDeny: () -> Unit,
): AndroidIpcApprovalState {
    val currentOnApprove by rememberUpdatedState(onApprove)
    val currentOnDeny by rememberUpdatedState(onDeny)
    var sourceSnapshot by remember(requestId) {
        mutableStateOf<AndroidIpcApprovalCoordinator.Snapshot?>(null)
    }
    val producer = remember(requestId) {
        AndroidIpcApprovalStateProducer(
            requestId = requestId,
            loadSnapshot = { id ->
                AndroidIpcApprovalCoordinator.snapshot(id)
                    ?.also { sourceSnapshot = it }
                    ?.toPresentationSnapshot()
            },
            onApprove = { selectedKeyIds ->
                currentOnApprove(selectedKeyIds)
            },
            onDeny = {
                currentOnDeny()
            },
        )
    }
    LaunchedEffect(producer) {
        producer.load()
    }

    val presentationState by producer.state.collectAsState()
    return when (val state = presentationState) {
        AndroidIpcApprovalPresentationState.Loading -> AndroidIpcApprovalState.Loading
        AndroidIpcApprovalPresentationState.Unavailable -> AndroidIpcApprovalState.Unavailable
        is AndroidIpcApprovalPresentationState.Ready -> {
            val current = sourceSnapshot
                ?.takeIf { it.id == state.request.id }
                ?: return AndroidIpcApprovalState.Loading
            AndroidIpcApprovalState.Ready(
                appLabel = state.request.appLabel,
                packageName = state.request.packageName,
                protocolLabel = current.protocolLabel,
                operation = current.operation,
                candidates = current.candidates,
                selectedKeyIds = state.selectedKeyIds,
                allowMultiple = state.request.allowMultiple,
                allowEmpty = state.request.allowEmpty,
                registerApp = state.request.registerApp,
                requiresAuthentication = state.request.requiresAuthentication,
                onSelect = state.onSelect,
                onApprove = state.onApprove,
                onDeny = state.onDeny,
            )
        }
    }
}

private fun AndroidIpcApprovalCoordinator.Snapshot.toPresentationSnapshot() =
    AndroidIpcApprovalSnapshot(
        id = id,
        appLabel = appLabel,
        packageName = packageName,
        candidates = candidates.map(AndroidIpcApprovalCoordinator.Candidate::toPresentationCandidate),
        allowMultiple = allowMultiple,
        allowEmpty = allowEmpty,
        registerApp = registerApp,
        requiresAuthentication = requiresAuthentication,
    )

private fun AndroidIpcApprovalCoordinator.Candidate.toPresentationCandidate() =
    AndroidIpcApprovalCandidate(
        id = id,
        name = name,
        description = description,
        preselected = preselected,
    )

internal fun initialAndroidIpcApprovalSelection(
    candidates: List<AndroidIpcApprovalCoordinator.Candidate>,
): Set<String> = initialPresentationSelection(
    candidates.map(AndroidIpcApprovalCoordinator.Candidate::toPresentationCandidate),
)
