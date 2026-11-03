package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddCliAgentUsageHistoryRequest
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingCliAgentUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.enqueueEvent
import com.artemchep.keyguard.common.service.session.CliAgentSessionAccess
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Writes the request log of the CLI agent: into the vault while it is
 * unlocked, otherwise into the pending queue that is flushed on the next
 * unlock. A failure to record never reaches the client.
 */
class CliAgentUsageRecorder(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: CliAgentSessionAccess,
    private val queue: PendingUsageHistoryQueue,
    private val json: Json,
    private val logRepository: LogRepository,
) {
    companion object {
        private const val TAG = "CliAgentUsageRecorder"
    }

    /** Identifies the requests of this app process. */
    @OptIn(ExperimentalUuidApi::class)
    private val sessionId = Uuid.random().toString()

    /**
     * Records the [response] to the request; `null` if the client left
     * before it was answered.
     */
    @Suppress("TooGenericExceptionCaught") // A failure to record never reaches the client.
    suspend fun record(
        usage: CliAgentUsageRecord,
        response: CliAgentMessages.Response.Kind?,
    ) {
        val errorCode = (response as? CliAgentMessages.Response.Kind.Error)?.value?.code
        val responseType = when {
            response == null -> CliAgentUsageHistoryResponseType.CANCELLED
            errorCode == null -> CliAgentUsageHistoryResponseType.SUCCESS
            else -> errorCode.toResponseType()
        }
        val storedErrorCode = errorCode
            ?.takeIf { responseType == CliAgentUsageHistoryResponseType.FAILURE }
        val caller = usage.caller?.let { caller ->
            runCatching { json.encodeToString(caller) }.getOrNull()
        }
        if (recordDirect(usage, caller, responseType, storedErrorCode)) {
            return
        }
        try {
            queue.enqueueEvent(
                protocol = PendingUsageHistory.Protocol.CLI_AGENT,
                sessionId = sessionId,
                caller = caller,
                requestType = usage.request.name,
                responseType = responseType.name,
                cipherId = usage.cipherId,
                cliAgent = PendingCliAgentUsageHistory(
                    client = usage.client.name,
                    clientName = usage.clientName,
                    errorCode = storedErrorCode,
                    command = usage.command,
                    path = usage.path,
                ),
                coalescenceKey = usage.coalescenceKey,
            ).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Failed to queue CLI agent usage history: ${e.message}", LogLevel.ERROR)
        }
    }

    /** Writes into the unlocked vault; `false` to fall back to the pending queue. */
    @Suppress("TooGenericExceptionCaught") // Any failure falls back to the pending queue.
    private suspend fun recordDirect(
        usage: CliAgentUsageRecord,
        caller: String?,
        responseType: CliAgentUsageHistoryResponseType,
        errorCode: String?,
    ): Boolean = try {
        // The session may close while it is being resolved.
        val addHistory = (getVaultSession.valueOrNull as? MasterSession.Key)
            ?.takeIf { it.session.active.value }
            ?.let(sessionAccess::invoke)
        if (addHistory != null) {
            val request = AddCliAgentUsageHistoryRequest(
                cipherId = usage.cipherId,
                sessionId = sessionId,
                client = usage.client,
                caller = caller,
                clientName = usage.clientName,
                request = usage.request,
                response = responseType,
                errorCode = errorCode,
                command = usage.command,
                path = usage.path,
            )
            addHistory(request).bind()
            true
        } else {
            false
        }
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to record CLI agent usage history: ${e.message}", LogLevel.ERROR)
        false
    }
}

private fun String.toResponseType(): CliAgentUsageHistoryResponseType = when (this) {
    "user_denied" -> CliAgentUsageHistoryResponseType.USER_DENIED
    "approval_timeout" -> CliAgentUsageHistoryResponseType.TIMED_OUT
    "vault_locked" -> CliAgentUsageHistoryResponseType.VAULT_LOCKED
    "not_found" -> CliAgentUsageHistoryResponseType.NOT_FOUND
    else -> CliAgentUsageHistoryResponseType.FAILURE
}
