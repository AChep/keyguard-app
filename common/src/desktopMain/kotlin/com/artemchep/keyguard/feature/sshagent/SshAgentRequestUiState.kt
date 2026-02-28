package com.artemchep.keyguard.feature.sshagent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class SshAgentRequestUiState(
    val request: SshAgentRequest,
    val onRequestHandled: () -> Unit,
)

/**
 * Collects SSH agent requests and exposes the active request for UI rendering.
 *
 * Requests are queued in arrival order. When [SshAgentRequestUiState.onRequestHandled]
 * is called for the current request, this emits [Loadable.Loading] for [transitionDelay]
 * and then emits either the next queued request or `null`.
 */
@Composable
fun rememberSshAgentRequestUiState(
    requestsFlow: Flow<SshAgentRequest>,
    transitionDelay: Duration = 500.milliseconds,
): Loadable<SshAgentRequestUiState>? {
    val scope = rememberCoroutineScope()
    val queue = remember { ArrayDeque<SshAgentRequest>() }
    var request by remember {
        mutableStateOf<Loadable<SshAgentRequest>?>(null)
    }
    var transitionJob by remember { mutableStateOf<Job?>(null) }

    fun showNextRequestIfIdle() {
        if (transitionJob?.isActive == true) return
        val currentRequest = (request as? Loadable.Ok)?.value
        if (currentRequest != null) return

        val nextRequest = if (queue.isEmpty()) null else queue.removeFirst()
        if (nextRequest == null) return
        request = Loadable.Ok(nextRequest)
    }

    LaunchedEffect(requestsFlow) {
        requestsFlow.collect { nextRequest ->
            queue.addLast(nextRequest)
            showNextRequestIfIdle()
        }
    }

    val onRequestHandled = remember(scope, transitionDelay) {
        {
            val currentRequest = (request as? Loadable.Ok)?.value
            val isTransitioning = transitionJob?.isActive == true
            if (currentRequest != null && !isTransitioning) {
                transitionJob = scope.launch {
                    request = Loadable.Loading
                    delay(transitionDelay)
                    request = if (queue.isEmpty()) {
                        null
                    } else {
                        Loadable.Ok(queue.removeFirst())
                    }
                }
            }
        }
    }

    return remember(request, onRequestHandled) {
        request?.map {
            SshAgentRequestUiState(
                request = it,
                onRequestHandled = onRequestHandled,
            )
        }
    }
}
