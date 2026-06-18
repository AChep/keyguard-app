package com.artemchep.keyguard.android.sshagent

import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentGetListRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.Clock
import kotlin.time.Duration

internal enum class AndroidSshAgentPromptKind {
    Unlock,
    Sign,
}

internal class AndroidSshAgentRequestFlow(
    private val enqueueRequest: suspend (SshAgentRequest) -> Unit,
    private val onRequestQueued: suspend (SshAgentRequest, AndroidSshAgentPromptKind, Boolean) -> Unit,
    private val onRequestFinished: suspend (String?) -> Unit = {},
    private val showRequestUi: suspend () -> Boolean,
) {
    suspend fun requestVaultUnlock(
        caller: SshAgentMessages.CallerIdentity?,
        notificationTag: String?,
        timeout: Duration,
    ): Boolean = enqueueAndAwait(
        request = SshAgentGetListRequest(
            caller = caller,
            notificationTag = notificationTag,
            expiresAt = Clock.System.now() + timeout,
            deferred = CompletableDeferred(),
        ),
        promptKind = AndroidSshAgentPromptKind.Unlock,
    )

    suspend fun requestSigningApproval(
        caller: SshAgentMessages.CallerIdentity?,
        keyName: String,
        keyFingerprint: String,
        notificationTag: String?,
        timeout: Duration,
    ): Boolean = enqueueAndAwait(
        request = SshAgentApprovalRequest(
            keyName = keyName,
            keyFingerprint = keyFingerprint,
            caller = caller,
            notificationTag = notificationTag,
            expiresAt = Clock.System.now() + timeout,
            deferred = CompletableDeferred(),
        ),
        promptKind = AndroidSshAgentPromptKind.Sign,
    )

    private suspend fun enqueueAndAwait(
        request: SshAgentRequest,
        promptKind: AndroidSshAgentPromptKind,
    ): Boolean {
        try {
            enqueueRequest(request)
            val requestUiShown = showRequestUi()
            onRequestQueued(request, promptKind, requestUiShown)
            return request.deferred.await()
        } finally {
            onRequestFinished(request.notificationTag)
        }
    }
}

@OptIn(ExperimentalContracts::class)
internal fun shouldAutoCompleteAndroidSshRequest(
    request: SshAgentRequest?,
    isVaultUnlocked: Boolean,
): Boolean {
    contract {
        returns(true) implies (request is SshAgentGetListRequest)
    }

    return isVaultUnlocked && request is SshAgentGetListRequest
}
