package com.artemchep.keyguard.feature.agent

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
import com.artemchep.keyguard.common.service.agent.AgentRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class AgentRequestUiState<out T : AgentRequest>(
    val request: T,
    val onRequestHandled: () -> Unit,
)

/**
 * Collects agent requests and exposes the active request for UI rendering.
 *
 * Requests are queued in arrival order. When [AgentRequestUiState.onRequestHandled]
 * is called for the current request, this emits [Loadable.Loading] for [transitionDelay]
 * and then emits either the next queued request or `null`.
 */
@Composable
fun <T : AgentRequest> rememberAgentRequestUiState(
    requestsFlow: Flow<T>,
    transitionDelay: Duration = 500.milliseconds,
): Loadable<AgentRequestUiState<T>>? {
    val scope = rememberCoroutineScope()
    val queue = remember { ArrayDeque<T>() }
    var request by remember {
        mutableStateOf<Loadable<T>?>(null)
    }
    var transitionJob by remember { mutableStateOf<Job?>(null) }

    fun showNextRequestIfIdle() {
        if (transitionJob?.isActive == true) return
        val currentRequest = (request as? Loadable.Ok)?.value
        if (currentRequest != null) return

        val nextRequest = queue.removeFirstUnresolvedRequestOrNull()
            ?: return
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
                    request = queue.removeFirstUnresolvedRequestOrNull()
                        ?.let { Loadable.Ok(it) }
                }
            }
        }
    }

    return remember(request, onRequestHandled) {
        request?.map {
            AgentRequestUiState(
                request = it,
                onRequestHandled = onRequestHandled,
            )
        }
    }
}

internal fun <T : AgentRequest> ArrayDeque<T>.removeFirstUnresolvedRequestOrNull(): T? {
    while (isNotEmpty()) {
        val request = removeFirst()
        if (!request.deferred.isCompleted) {
            return request
        }
    }
    return null
}
