package com.artemchep.keyguard.feature.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.map
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.common.service.agent.logAgentRequestDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Immutable
data class AgentRequestUiState<out T>(
    val request: T,
    val id: String,
    val expiresAt: Instant,
    /** Answers the request; the [reason] is only logged. */
    val onDecision: (approved: Boolean, reason: String) -> Unit,
)

private val AGENT_REQUEST_TRANSITION_DELAY = 500.milliseconds

/**
 * Exposes the first of the [pending] requests for UI rendering.
 *
 * Once the shown request is answered, or leaves the queue because it expired
 * or its caller gave up, this emits [Loadable.Loading] for a short pause,
 * so the next request never receives input meant for the previous one, and
 * then emits the next pending request or `null`.
 */
@Composable
fun <T : Any> rememberAgentRequestUiState(
    pending: StateFlow<List<T>>,
    idOf: (T) -> String,
    expiresAtOf: (T) -> Instant,
    respond: (id: String, approved: Boolean) -> Boolean,
    logType: String,
): Loadable<AgentRequestUiState<T>>? {
    val scope = rememberCoroutineScope()
    val updatedIdOf by rememberUpdatedState(idOf)
    val presenter = remember(pending) {
        AgentRequestPresenter<T>(
            idOf = { updatedIdOf(it) },
            scope = scope,
            transitionDelay = AGENT_REQUEST_TRANSITION_DELAY,
        )
    }
    LaunchedEffect(presenter) {
        pending.collect(presenter::onPending)
    }
    val updatedExpiresAtOf by rememberUpdatedState(expiresAtOf)
    val updatedRespond by rememberUpdatedState(respond)
    val state by presenter.state.collectAsState()
    return remember(state, presenter, logType) {
        state?.map { request ->
            val id = updatedIdOf(request)
            AgentRequestUiState(
                request = request,
                id = id,
                expiresAt = updatedExpiresAtOf(request),
                onDecision = { approved, reason ->
                    val completed = updatedRespond(id, approved)
                    logAgentRequestDecision(
                        logType = logType,
                        id = id,
                        value = approved,
                        reason = reason,
                        completed = completed,
                    )
                    presenter.onHandled(id)
                },
            )
        }
    }
}

@Composable
fun <T : AgentRequest> rememberAgentRequestUiState(
    broker: AgentApprovalBroker<T>,
    logType: String,
): Loadable<AgentRequestUiState<T>>? = rememberAgentRequestUiState(
    pending = broker.pending,
    idOf = AgentRequest::id,
    expiresAtOf = AgentRequest::expiresAt,
    respond = broker::respond,
    logType = logType,
)

/** The request queue state behind [rememberAgentRequestUiState]; confined to one thread. */
internal class AgentRequestPresenter<T : Any>(
    private val idOf: (T) -> String,
    private val scope: CoroutineScope,
    private val transitionDelay: Duration,
) {
    private val mutableState = MutableStateFlow<Loadable<T>?>(null)
    private var pending: List<T> = emptyList()
    private var transition: Job? = null

    val state = mutableState.asStateFlow()

    fun onPending(requests: List<T>) {
        pending = requests
        if (transition?.isActive == true) return
        val current = (mutableState.value as? Loadable.Ok)?.value
        if (current == null) {
            mutableState.value = requests.firstOrNull()?.let { Loadable.Ok(it) }
            return
        }
        // Keep the shown request, with its latest data, until it leaves the queue.
        val id = idOf(current)
        val updated = requests.firstOrNull { idOf(it) == id }
        if (updated != null) {
            mutableState.value = Loadable.Ok(updated)
        } else {
            startTransition()
        }
    }

    fun onHandled(id: String) {
        val current = (mutableState.value as? Loadable.Ok)?.value
            ?: return
        if (idOf(current) == id && transition?.isActive != true) {
            startTransition()
        }
    }

    private fun startTransition() {
        mutableState.value = Loadable.Loading
        transition = scope.launch {
            delay(transitionDelay)
            mutableState.value = pending.firstOrNull()?.let { Loadable.Ok(it) }
        }
    }
}
