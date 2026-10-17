package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Instant

/** A pending prompt of any agent, as the shared approval window shows it. */
sealed interface AgentPrompt {
    val id: String
    val expiresAt: Instant

    data class Ssh(val request: SshAgentApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }

    data class Gpg(val request: GpgAgentApprovalRequest) : AgentPrompt {
        override val id: String get() = request.id
        override val expiresAt: Instant get() = request.expiresAt
    }
}

/**
 * The pending approval prompts of every agent, so one surface shows them
 * one at a time instead of each agent opening its own window over the
 * others.
 */
class AgentApprovalCenter(
    private val ssh: AgentApprovalBroker<SshAgentApprovalRequest>,
    private val gpg: AgentApprovalBroker<GpgAgentApprovalRequest>,
    scope: CoroutineScope,
) {
    /** The pending prompts, oldest first. */
    val pending: StateFlow<List<AgentPrompt>> = combine(
        ssh.pending,
        gpg.pending,
    ) { sshPrompts, gpgPrompts ->
        val prompts = sshPrompts.map(AgentPrompt::Ssh) +
            gpgPrompts.map(AgentPrompt::Gpg)
        // A prompt expires a fixed time after it is shown, so its deadline orders it.
        prompts.sortedBy { it.expiresAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Answers the prompt [id] of whichever agent owns it; `false` if it is no longer pending. */
    fun respond(id: String, approved: Boolean): Boolean =
        ssh.respond(id, approved) ||
            gpg.respond(id, approved)
}
