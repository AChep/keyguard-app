package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

sealed interface SshAgentRequest : AgentRequest {
    override val caller: SshAgentMessages.CallerIdentity?

    override val notificationTag: String?

    override val expiresAt: Instant

    override val deferred: CompletableDeferred<Boolean>

    override val logType: String
        get() = when (this) {
            is SshAgentApprovalRequest -> "approval"
            is SshAgentGetListRequest -> "get_list"
        }
}

/**
 * Represents a pending SSH signing approval request.
 *
 * The IPC server creates one of these when it receives a sign request
 * and suspends on [deferred] until the user approves or denies via the
 * Compose UI. The request auto-denies at [expiresAt] if the user doesn't respond.
 *
 * @param keyName The display name of the SSH key being used.
 * @param keyFingerprint The fingerprint of the SSH key (e.g. SHA256:...).
 * @param expiresAt The instant at which the request is automatically denied.
 * @param deferred Completed with `true` if the user approves, `false` if denied.
 */
data class SshAgentApprovalRequest(
    val keyName: String,
    val keyFingerprint: String,
    /**
     * Identity of the vault entry holding the key, when known; lets
     * the approval surface link the request to the entry.
     */
    val accountId: String?,
    val cipherId: String?,
    override val caller: SshAgentMessages.CallerIdentity?,
    override val notificationTag: String? = null,
    override val expiresAt: Instant,
    override val deferred: CompletableDeferred<Boolean>,
) : SshAgentRequest

/**
 * Represents a pending get-list request triggered by an SSH agent
 * key listing operation that arrived while the vault was locked.
 *
 * The IPC server creates one of these and suspends on [deferred] until
 * the user unlocks the vault or dismisses the prompt.
 *
 * Multiple concurrent list-key requests that need the vault unlocked
 * share a single [SshAgentGetListRequest] (coalesced in [SshAgentManager]).
 *
 * @param expiresAt The instant at which the request is automatically denied.
 * @param deferred Completed with `true` if the vault was unlocked, `false` if
 *   the user dismissed or the request expired.
 */
data class SshAgentGetListRequest(
    override val caller: SshAgentMessages.CallerIdentity?,
    override val notificationTag: String? = null,
    override val expiresAt: Instant,
    override val deferred: CompletableDeferred<Boolean>,
) : SshAgentRequest
