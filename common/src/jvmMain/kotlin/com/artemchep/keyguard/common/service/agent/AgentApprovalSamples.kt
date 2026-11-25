package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Sample approval prompts that the Development settings send through the
 * approval window. No agent waits for them, so answering one releases,
 * remembers and records nothing.
 */
class AgentApprovalSamples {
    private val broker = AgentApprovalBroker<AgentPrompt.Sample>(
        idOf = AgentPrompt.Sample::id,
    )

    /** The pending samples, in arrival order. */
    val pending: StateFlow<List<AgentPrompt.Sample>> get() = broker.pending

    /** Answers the sample [id]; `false` if it is no longer pending. */
    fun respond(id: String, approved: Boolean): Boolean = broker.respond(id, approved)

    /** Shows the sample prompt that [build] makes and waits for the user's decision. */
    suspend fun show(
        build: (id: String, expiresAt: Instant) -> AgentPrompt,
    ): AgentApprovalBroker.Result = broker.request { expiresAt ->
        val id = "sample-${Uuid.random()}"
        AgentPrompt.Sample(build(id, expiresAt))
    }
}
