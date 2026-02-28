package com.artemchep.keyguard.common.service.sshagent

import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration

sealed interface SshAgentRequest {
    val caller: SshAgentMessages.CallerIdentity?

    val timeout: Duration

    val deferred: CompletableDeferred<Boolean>
}

/**
 * Represents a pending SSH signing approval request.
 *
 * The IPC server creates one of these when it receives a sign request
 * and suspends on [deferred] until the user approves or denies via the
 * Compose UI. The [timeout] duration auto-denies if the user doesn't respond.
 *
 * @param keyName The display name of the SSH key being used.
 * @param keyFingerprint The fingerprint of the SSH key (e.g. SHA256:...).
 * @param timeout The duration after which the request is automatically denied.
 * @param deferred Completed with `true` if the user approves, `false` if denied.
 */
data class SshAgentApprovalRequest(
    val keyName: String,
    val keyFingerprint: String,
    override val caller: SshAgentMessages.CallerIdentity?,
    override val timeout: Duration,
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
 * @param timeout The duration after which the request is automatically denied.
 * @param deferred Completed with `true` if the vault was unlocked, `false` if
 *   the user dismissed or the timeout expired.
 */
data class SshAgentGetListRequest(
    override val caller: SshAgentMessages.CallerIdentity?,
    override val timeout: Duration,
    override val deferred: CompletableDeferred<Boolean>,
) : SshAgentRequest
