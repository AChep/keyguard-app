package com.artemchep.keyguard.feature.android.ipc.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AndroidIpcApprovalCandidate(
    val id: String,
    val name: String,
    val description: String,
    val preselected: Boolean = false,
)

data class AndroidIpcApprovalSnapshot(
    val id: String,
    val appLabel: String,
    val packageName: String,
    val candidates: List<AndroidIpcApprovalCandidate>,
    val allowMultiple: Boolean,
    val allowEmpty: Boolean,
    val registerApp: Boolean,
    val requiresAuthentication: Boolean,
)

sealed interface AndroidIpcApprovalPresentationState {
    data object Loading : AndroidIpcApprovalPresentationState

    data object Unavailable : AndroidIpcApprovalPresentationState

    data class Ready(
        val request: AndroidIpcApprovalSnapshot,
        val selectedKeyIds: Set<String>,
        val onSelect: (String) -> Unit,
        val onApprove: () -> Unit,
        val onDeny: () -> Unit,
    ) : AndroidIpcApprovalPresentationState
}

/**
 * Owns the presentation state for one IPC approval request without depending on Android or Compose.
 */
class AndroidIpcApprovalStateProducer(
    private val requestId: String,
    private val loadSnapshot: suspend (String) -> AndroidIpcApprovalSnapshot?,
    private val onApprove: (Set<String>) -> Unit,
    private val onDeny: () -> Unit,
) {
    private val mutableState = MutableStateFlow<AndroidIpcApprovalPresentationState>(
        AndroidIpcApprovalPresentationState.Loading,
    )

    val state: StateFlow<AndroidIpcApprovalPresentationState> = mutableState.asStateFlow()

    suspend fun load() {
        val snapshot = loadSnapshot(requestId)
        if (snapshot == null) {
            mutableState.value = AndroidIpcApprovalPresentationState.Unavailable
            return
        }

        mutableState.value = snapshot.toReadyState(
            selectedKeyIds = initialAndroidIpcApprovalSelection(snapshot.candidates),
        )
    }

    private fun AndroidIpcApprovalSnapshot.toReadyState(
        selectedKeyIds: Set<String>,
    ): AndroidIpcApprovalPresentationState.Ready = AndroidIpcApprovalPresentationState.Ready(
        request = this,
        selectedKeyIds = selectedKeyIds,
        onSelect = { candidateId ->
            mutableState.update { state ->
                val current = state as? AndroidIpcApprovalPresentationState.Ready
                    ?: return@update state
                val selection = if (current.request.allowMultiple) {
                    if (candidateId in current.selectedKeyIds) {
                        current.selectedKeyIds - candidateId
                    } else {
                        current.selectedKeyIds + candidateId
                    }
                } else {
                    setOf(candidateId)
                }
                current.copy(selectedKeyIds = selection)
            }
        },
        onApprove = {
            val current = mutableState.value as? AndroidIpcApprovalPresentationState.Ready
            if (
                current != null &&
                (current.selectedKeyIds.isNotEmpty() || current.request.allowEmpty)
            ) {
                onApprove(current.selectedKeyIds)
            }
        },
        onDeny = onDeny,
    )
}

fun initialAndroidIpcApprovalSelection(
    candidates: List<AndroidIpcApprovalCandidate>,
): Set<String> {
    val candidate = candidates
        .singleOrNull(AndroidIpcApprovalCandidate::preselected)
        ?: candidates.singleOrNull()
    return setOfNotNull(candidate?.id)
}
