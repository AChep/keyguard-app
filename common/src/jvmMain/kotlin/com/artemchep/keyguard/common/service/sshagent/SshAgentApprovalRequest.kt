package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlin.time.Instant

/**
 * Represents a pending SSH signing approval request.
 *
 * The IPC server creates one of these when it receives a sign request
 * and waits until the user approves or denies it in the approval UI.
 * The request auto-denies at [expiresAt] if the user doesn't respond.
 *
 * @param keyName The display name of the SSH key being used.
 * @param keyFingerprint The fingerprint of the SSH key (e.g. SHA256:...).
 * @param expiresAt The instant at which the request is automatically denied.
 */
data class SshAgentApprovalRequest(
    override val id: String,
    val keyName: String,
    val keyFingerprint: String,
    /**
     * Identity of the vault entry holding the key, when known; lets
     * the approval surface link the request to the entry.
     */
    val accountId: String?,
    val cipherId: String?,
    override val caller: CallerIdentity?,
    /** The notification that represents the request, if any. */
    val notificationTag: String? = null,
    override val expiresAt: Instant,
) : AgentRequest

internal fun SshAgentApprovalPrompt.toApprovalRequest(
    id: String,
    expiresAt: Instant,
    notificationTag: String? = null,
): SshAgentApprovalRequest = SshAgentApprovalRequest(
    id = id,
    keyName = keyName,
    keyFingerprint = keyFingerprint,
    accountId = accountId,
    cipherId = cipherId,
    caller = caller,
    notificationTag = notificationTag,
    expiresAt = expiresAt,
)
