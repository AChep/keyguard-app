package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How long an approval prompt waits for the user before it is denied. */
val AGENT_APPROVAL_PROMPT_TIMEOUT: Duration = 60.seconds

/**
 * The approval prompts of one agent that wait for the user, in arrival order.
 *
 * A prompt stays [pending] while its [request] suspends: until the user
 * [responds][respond], its deadline passes, or the requesting coroutine is
 * cancelled. Every exit removes the prompt, so a cancelled caller never
 * leaves a stale prompt on screen.
 *
 * The broker owns the deadlines: a prompt expires [promptTimeout] after it
 * is shown, unless its request names an earlier [deadline].
 *
 * @param idOf A unique identifier of a prompt, used to [respond] to it.
 */
class AgentApprovalBroker<P : Any>(
    private val idOf: (P) -> String,
    private val clock: Clock = Clock.System,
    val promptTimeout: Duration = AGENT_APPROVAL_PROMPT_TIMEOUT,
) {
    enum class Result {
        Approved,
        Denied,
        Expired,
    }

    private val mutablePending = MutableStateFlow<List<P>>(emptyList())
    private val responses = MutableStateFlow<Map<String, CompletableDeferred<Boolean>>>(emptyMap())

    val pending: StateFlow<List<P>> = mutablePending.asStateFlow()

    /**
     * The deadline of a prompt shown now. The prompts of one request share
     * one deadline, so the request as a whole ends within [promptTimeout].
     */
    fun deadline(): Instant = clock.now() + promptTimeout

    /**
     * Shows the prompt that [build] creates and waits for the user's
     * decision. The prompt receives the [deadline] it is denied at, and
     * must show it as its expiry.
     */
    suspend fun request(
        deadline: Instant = deadline(),
        build: (expiresAt: Instant) -> P,
    ): Result {
        val prompt = build(deadline)
        val id = idOf(prompt)
        val response = CompletableDeferred<Boolean>()
        // Register the response first: a prompt on screen can always be answered.
        responses.update { it + (id to response) }
        mutablePending.update { it + prompt }
        try {
            val approved = withTimeoutOrNull(deadline - clock.now()) {
                response.await()
            } ?: return Result.Expired
            return if (approved) Result.Approved else Result.Denied
        } finally {
            responses.update { it - id }
            mutablePending.update { list -> list.filterNot { idOf(it) == id } }
            response.cancel()
        }
    }

    /**
     * Answers the prompt [id] and removes it from [pending] at once. Returns
     * `false` if the prompt is no longer pending.
     */
    fun respond(id: String, approved: Boolean): Boolean {
        val response = responses.value[id]
            ?: return false
        val completed = response.complete(approved)
        mutablePending.update { list -> list.filterNot { idOf(it) == id } }
        return completed
    }

    /** Replaces the displayed data of the pending prompts; [transform] must keep their ids. */
    fun update(transform: (P) -> P) {
        mutablePending.update { list -> list.map(transform) }
    }

    /** Denies every pending prompt and removes it from [pending] at once. */
    fun denyAll() {
        val denied = responses.getAndUpdate { emptyMap() }
        mutablePending.update { list -> list.filterNot { idOf(it) in denied } }
        denied.values.forEach { it.complete(false) }
    }
}
