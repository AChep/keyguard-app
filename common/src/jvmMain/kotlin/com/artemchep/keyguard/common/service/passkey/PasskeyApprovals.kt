package com.artemchep.keyguard.common.service.passkey

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** The approval prompts of the operating system's passkey requests, for the shared approval window. */
interface PasskeyApprovals {
    /** The prompts waiting for the user. */
    val approvals: StateFlow<List<PasskeyApprovalRequest>>

    /** True while the operating system verifies the user: the approval window stays off screen. */
    val verifying: Flow<Boolean>

    /** Answers the prompt [id]; `false` if it is no longer pending. */
    fun respondToApproval(id: String, approved: Boolean): Boolean

    /** Selects the passkey [choice] of the sign-in prompt [id]; `false` if not pending or out of range. */
    fun selectPasskey(id: String, choice: Int): Boolean

    /**
     * Confirms the user's identity for the selected passkey of the prompt [id] with [verify];
     * `true` if the selected passkey needs no confirmation. A confirmation holds only for
     * the passkey it was made for.
     */
    suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean
}

data class PasskeyApprovalRequest(
    val id: String,
    val rpId: String,
    val expiresAt: Instant,
    val operation: PasskeyApprovalOperation,
)

sealed interface PasskeyApprovalOperation {
    /** The vault must be unlocked to serve the request. */
    data object Unlock : PasskeyApprovalOperation

    /** Signing in with one of [choices], which the user picks. Not empty; most recently used first. */
    data class SignIn(val choices: List<PasskeyApprovalChoice>) : PasskeyApprovalOperation
}

data class PasskeyApprovalChoice(
    val name: String,
    val username: String?,
    val accountName: String?,
    /** Approving requires the user to confirm their identity. */
    val reprompt: Boolean,
)
