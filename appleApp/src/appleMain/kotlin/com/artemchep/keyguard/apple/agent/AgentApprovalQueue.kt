package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_PROMPT_TIMEOUT
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.buildAgentApprovalCallerInfo
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The approval prompts of one agent, as snapshots for its native panel.
 *
 * @param toSnapshot Builds the snapshot of a prompt with its id, timeout and deadline.
 * @param timeout How long a prompt waits for the user; the broker's default.
 */
internal class AgentApprovalQueue<P, S : Any>(
    private val idPrefix: String,
    idOf: (S) -> String,
    private val toSnapshot: P.(id: String, timeoutMs: Long, expiresAtEpochMs: Long) -> S,
    timeout: Duration = AGENT_APPROVAL_PROMPT_TIMEOUT,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val counter = MutableStateFlow(0L)
    private val broker = AgentApprovalBroker(
        idOf = idOf,
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowEpochMs())
        },
        promptTimeout = timeout,
    )

    val requests: StateFlow<List<S>> = broker.pending

    /** Waits for the user's decision, until [deadline]. */
    suspend fun await(
        prompt: P,
        deadline: Instant = broker.deadline(),
    ): Boolean {
        val id = "$idPrefix-${counter.updateAndGet { it + 1 }}"
        val result = broker.request(deadline = deadline) { expiresAt ->
            prompt.toSnapshot(id, broker.promptTimeout.inWholeMilliseconds, expiresAt.toEpochMilliseconds())
        }
        return result == AgentApprovalBroker.Result.Approved
    }

    fun resolve(id: String, approved: Boolean) {
        broker.respond(id, approved)
    }

    fun denyAll() = broker.denyAll()

    /** Applies the shared lock rule: a lock denies the pending prompts. */
    suspend fun denyWhenSessionEnds(getVaultSession: GetVaultSession) =
        broker.denyWhenSessionEnds(getVaultSession())
}

/** The verified name of an approval caller, as the desktop shows it; empty if unknown. */
internal fun AgentCallerIdentity?.snapshotCallerName(): String =
    buildAgentApprovalCallerInfo(this)?.primaryLabel.orEmpty()

/** The executable that asked for approval; empty if unknown. */
internal fun AgentCallerIdentity?.snapshotCallerPath(): String =
    this?.executablePath
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH)
        .orEmpty()
