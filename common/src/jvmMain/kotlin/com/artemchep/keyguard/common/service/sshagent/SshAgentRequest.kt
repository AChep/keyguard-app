package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.CompletableDeferred
import java.util.logging.Logger
import kotlin.time.Instant

sealed interface SshAgentRequest {
    val caller: SshAgentMessages.CallerIdentity?

    val notificationTag: String?

    val expiresAt: Instant

    val deferred: CompletableDeferred<Boolean>
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

private val sshAgentRequestLogger = Logger.getLogger(SshAgentRequest::class.java.name)

internal fun SshAgentRequest.completeWithLog(
    value: Boolean,
    reason: String,
): Boolean {
    val completed = deferred.complete(value)
    if (!isRelease) {
        log(
            value = value,
            reason = reason,
            completed = completed,
        )
    }
    return completed
}

private fun SshAgentRequest.log(
    value: Boolean,
    reason: String,
    completed: Boolean,
) {
    val requestType = when (this) {
        is SshAgentApprovalRequest -> "approval"
        is SshAgentGetListRequest -> "get_list"
    }
    val caller = caller?.let {
        it.appName.takeIf(String::isNotBlank)
            ?: it.processName.takeIf(String::isNotBlank)
            ?: it.executablePath.takeIf(String::isNotBlank)
    } ?: "unknown"
    val message =
        "Completing SSH agent request type=$requestType result=$value completed=$completed " +
                "reason=$reason notificationTag=$notificationTag caller=$caller"
    if (completed) {
        sshAgentRequestLogger.info(message)
    } else {
        sshAgentRequestLogger.warning(message)
    }
}
