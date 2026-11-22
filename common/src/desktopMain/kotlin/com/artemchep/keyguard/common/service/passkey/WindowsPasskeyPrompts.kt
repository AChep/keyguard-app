package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalBroker
import com.artemchep.keyguard.common.service.agent.AgentApprovalSelections
import com.artemchep.keyguard.common.service.agent.denyWhenSessionEnds
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The approval prompts of the Windows requests, and the passkey the user selected in each
 * before answering it.
 */
internal class WindowsPasskeyPrompts(
    clock: Clock = Clock.System,
) {
    private val broker = AgentApprovalBroker<PasskeyApprovalRequest>(
        idOf = { it.id },
        clock = clock,
    )

    /** The prompts waiting for the user. */
    val pending: StateFlow<List<PasskeyApprovalRequest>> = broker.pending

    private val selections = AgentApprovalSelections(isPending = { id -> pending.value.any { it.id == id } })

    /** Denies the pending prompts whenever an unlocked vault session ends. */
    suspend fun denyWhenSessionEnds(sessions: Flow<MasterSession>) = broker.denyWhenSessionEnds(sessions)

    /** See [PasskeyApprovals.respondToApproval]. */
    fun respond(id: String, approved: Boolean): Boolean = broker.respond(id, approved)

    /** See [PasskeyApprovals.selectPasskey]. */
    fun selectPasskey(id: String, choice: Int): Boolean {
        // Only a sign-in prompt has passkeys to select.
        val signIn = pending.value.any { it.id == id && it.operation is PasskeyApprovalOperation.SignIn }
        return signIn && selections.select(id, choice)
    }

    /** See [PasskeyApprovals.confirmIdentity]. */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean =
        selections.confirmIdentity(id, verify)

    /**
     * Shows the prompt of [operation] for the relying party [rpId] and waits for the user
     * until [expiresAt], Windows' own deadline; expired if Windows stops waiting first.
     * Approving a passkey that requires the user to [confirm their identity][confirmIdentity]
     * without that denies it.
     */
    suspend fun ask(
        rpId: String,
        expiresAt: Instant,
        operation: PasskeyApprovalOperation,
    ): AgentApprovalSelections.Answer {
        val id = UUID.randomUUID().toString()
        val requiresIdentity = (operation as? PasskeyApprovalOperation.SignIn)
            ?.choices?.map { it.reprompt }
            ?: listOf(false)
        return selections.track(id, requiresIdentity) {
            broker.request(deadline = expiresAt) { deadline ->
                PasskeyApprovalRequest(
                    id = id,
                    rpId = rpId,
                    expiresAt = deadline,
                    operation = operation,
                )
            }
        }
    }
}
