package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentRpcRequestContext
import com.artemchep.keyguard.common.service.agent.agentRpcRequestError

internal class SshAgentRpcHandler(
    private val requestProcessor: SshAgentRequestProcessor,
    private val authenticate: (AgentIpcMessages.AuthenticateRequest) -> Boolean = { false },
) {
    suspend fun processRequest(
        request: SshAgentMessages.IpcRequest,
        context: AgentRpcRequestContext,
    ): SshAgentMessages.IpcResponse {
        val variantCount = listOfNotNull(
            request.authenticate,
            request.listKeys,
            request.signData,
        ).size
        agentRpcRequestError(
            variantCount = variantCount,
            isAuthenticate = request.authenticate != null,
            context = context,
        )?.let { error ->
            return SshAgentMessages.IpcResponse(id = request.id, error = error)
        }

        return when {
            request.authenticate != null -> handleAuthenticate(
                requestId = request.id,
                request = request.authenticate,
            )

            request.listKeys != null -> handleListKeys(
                requestId = request.id,
                request = request.listKeys,
            )

            request.signData != null -> handleSignData(
                requestId = request.id,
                request = request.signData,
            )

            else -> errorResponse(
                requestId = request.id,
                message = "Unknown request type",
                code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
            )
        }
    }

    fun handleAuthenticate(
        requestId: Long,
        request: AgentIpcMessages.AuthenticateRequest,
    ): SshAgentMessages.IpcResponse = SshAgentMessages.IpcResponse(
        id = requestId,
        authenticate = AgentIpcMessages.AuthenticateResponse(
            success = authenticate(request),
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        ),
    )

    suspend fun handleListKeys(
        requestId: Long,
        request: SshAgentMessages.ListKeysRequest,
    ): SshAgentMessages.IpcResponse =
        when (val result = requestProcessor.listKeys(request.caller)) {
            is SshAgentRequestProcessor.ListKeysResult.Success -> {
                SshAgentMessages.IpcResponse(
                    id = requestId,
                    listKeys = result.response,
                )
            }

            SshAgentRequestProcessor.ListKeysResult.VaultLocked -> errorResponse(
                requestId = requestId,
                message = "Vault is locked",
                code = AgentIpcMessages.ErrorCode.VAULT_LOCKED,
            )
        }

    suspend fun handleSignData(
        requestId: Long,
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentMessages.IpcResponse =
        when (val result = requestProcessor.signData(request)) {
            is SshAgentRequestProcessor.SignDataResult.Success -> {
                SshAgentMessages.IpcResponse(
                    id = requestId,
                    signData = result.response,
                )
            }

            SshAgentRequestProcessor.SignDataResult.VaultLocked -> errorResponse(
                requestId = requestId,
                message = "Vault is locked",
                code = AgentIpcMessages.ErrorCode.VAULT_LOCKED,
            )

            SshAgentRequestProcessor.SignDataResult.UserDenied -> errorResponse(
                requestId = requestId,
                message = "User denied the signing request",
                code = AgentIpcMessages.ErrorCode.USER_DENIED,
            )

            SshAgentRequestProcessor.SignDataResult.KeyNotFound -> errorResponse(
                requestId = requestId,
                message = "Key not found for the requested public key",
                code = AgentIpcMessages.ErrorCode.KEY_NOT_FOUND,
            )

            is SshAgentRequestProcessor.SignDataResult.Failure -> errorResponse(
                requestId = requestId,
                message = result.message,
                code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
            )
        }

    private fun errorResponse(
        requestId: Long,
        message: String,
        code: Int,
    ) = SshAgentMessages.IpcResponse(
        id = requestId,
        error = AgentIpcMessages.ErrorResponse(
            message = message,
            code = code,
        ),
    )
}
