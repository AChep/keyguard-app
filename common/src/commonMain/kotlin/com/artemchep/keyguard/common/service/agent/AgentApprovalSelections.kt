package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The choice the user selected in each approval prompt, and whether they confirmed their
 * identity for it. Approving a choice that requires the identity without confirming it
 * denies the prompt. A confirmation holds only for the choice it was made for. A prompt
 * without choices is one choice.
 *
 * @param isPending Whether the prompt is still on screen; an ended prompt accepts no input.
 */
class AgentApprovalSelections(
    private val isPending: (id: String) -> Boolean,
) {
    sealed interface Answer {
        /** [identityConfirmed]: the [choice] required the identity, and the user confirmed it. */
        data class Approved(val choice: Int, val identityConfirmed: Boolean) : Answer

        data object Denied : Answer

        data object Expired : Answer
    }

    /** The state of each tracked prompt, by prompt id. */
    private val states = MutableStateFlow<Map<String, State>>(emptyMap())

    /**
     * Tracks the prompt [id] while [request] shows it, and maps its result to the [Answer].
     *
     * @param requiresIdentity Whether each choice requires the user to
     *   [confirm their identity][confirmIdentity]; not empty. The first choice is selected.
     */
    suspend fun track(
        id: String,
        requiresIdentity: List<Boolean>,
        request: suspend () -> AgentApprovalBroker.Result,
    ): Answer {
        require(requiresIdentity.isNotEmpty()) { "A prompt has at least one choice." }
        var state = State(requiresIdentity = requiresIdentity)
        states.update { it + (id to state) }
        val result = try {
            request()
        } finally {
            states.getAndUpdate { it - id }[id]?.let { state = it }
        }
        return when (result) {
            AgentApprovalBroker.Result.Approved -> {
                val required = state.requiresIdentity[state.choice]
                if (required && !state.verified) Answer.Denied else Answer.Approved(state.choice, required)
            }

            AgentApprovalBroker.Result.Denied -> Answer.Denied
            AgentApprovalBroker.Result.Expired -> Answer.Expired
        }
    }

    /**
     * Selects the [choice] of the pending prompt [id]; a new choice drops the confirmed
     * identity. Returns `false` if the prompt ended or has no such choice.
     */
    fun select(id: String, choice: Int): Boolean {
        if (!isPending(id)) return false
        var applied = false
        states.update { map ->
            val state = map[id]?.takeIf { choice in it.requiresIdentity.indices }
            applied = state != null
            // A confirmed identity holds only for the choice it was confirmed for.
            if (state == null || state.choice == choice) {
                map
            } else {
                map + (id to state.copy(choice = choice, verified = false))
            }
        }
        return applied
    }

    /**
     * Confirms the user's identity with [verify] for the selected choice of the pending
     * prompt [id], if the choice requires it. Returns `false` if the prompt ended, [verify]
     * failed, or the selection changed meanwhile.
     */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean {
        val state = states.value[id]?.takeIf { isPending(id) }
        return when {
            state == null -> false
            !state.requiresIdentity[state.choice] -> true
            // The prompt may end, or the selection change, while the user confirms their identity.
            else -> verify() && isPending(id) && markVerified(id, state.choice)
        }
    }

    /** Marks the identity confirmed for the [choice] of the prompt [id], unless the selection changed. */
    private fun markVerified(id: String, choice: Int): Boolean {
        var verified = false
        states.update { map ->
            val state = map[id]?.takeIf { it.choice == choice }
            verified = state != null
            if (state == null) map else map + (id to state.copy(verified = true))
        }
        return verified
    }

    /** What the user gave a prompt before answering it. */
    private data class State(
        /** Whether each choice requires the user to confirm their identity. */
        val requiresIdentity: List<Boolean>,
        /** The index of the selected choice. */
        val choice: Int = 0,
        /** The user confirmed their identity for the [choice]. */
        val verified: Boolean = false,
    )
}
