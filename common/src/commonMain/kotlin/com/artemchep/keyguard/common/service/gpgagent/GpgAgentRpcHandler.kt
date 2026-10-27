package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.AgentRpcRequestContext
import com.artemchep.keyguard.common.service.agent.agentRpcRequestError
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult

internal class GpgAgentRpcHandler(
    private val requestProcessor: GpgAgentRequestProcessor,
    private val authenticate: (AgentIpcMessages.AuthenticateRequest) -> Boolean = { false },
) {
    suspend fun processRequest(
        request: GpgAgentMessages.IpcRequest,
        context: AgentRpcRequestContext,
    ): GpgAgentMessages.IpcResponse {
        val variantCount = listOfNotNull(
            request.authenticate,
            request.listKeys,
            request.signHash,
            request.pkdecrypt,
        ).size
        agentRpcRequestError(
            variantCount = variantCount,
            isAuthenticate = request.authenticate != null,
            context = context,
        )?.let { error ->
            return GpgAgentMessages.IpcResponse(id = request.id, error = error)
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

            request.signHash != null -> handleSignHash(
                requestId = request.id,
                request = request.signHash,
            )

            request.pkdecrypt != null -> handlePkdecrypt(
                requestId = request.id,
                request = request.pkdecrypt,
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
    ): GpgAgentMessages.IpcResponse = GpgAgentMessages.IpcResponse(
        id = requestId,
        authenticate = AgentIpcMessages.AuthenticateResponse(
            success = authenticate(request),
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        ),
    )

    suspend fun handleListKeys(
        requestId: Long,
        request: GpgAgentMessages.ListKeysRequest,
    ): GpgAgentMessages.IpcResponse =
        when (val result = requestProcessor.listKeys(request.caller)) {
            is GpgAgentRequestProcessor.ListKeysResult.Success -> {
                GpgAgentMessages.IpcResponse(
                    id = requestId,
                    listKeys = result.response,
                )
            }

            GpgAgentRequestProcessor.ListKeysResult.VaultLocked -> errorResponse(
                requestId = requestId,
                message = "Vault is locked",
                code = AgentIpcMessages.ErrorCode.VAULT_LOCKED,
            )
        }

    suspend fun handleSignHash(
        requestId: Long,
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentMessages.IpcResponse = mapOperationResult(
        requestId = requestId,
        result = requestProcessor.signHash(request),
        deniedMessage = "User denied the signing request",
        onSuccess = { response ->
            GpgAgentMessages.IpcResponse(
                id = requestId,
                signHash = response,
            )
        },
    )

    suspend fun handlePkdecrypt(
        requestId: Long,
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentMessages.IpcResponse = mapOperationResult(
        requestId = requestId,
        result = requestProcessor.decrypt(request),
        deniedMessage = "User denied the decryption request",
        onSuccess = { response ->
            GpgAgentMessages.IpcResponse(
                id = requestId,
                pkdecrypt = response,
            )
        },
    )

    private fun <T> mapOperationResult(
        requestId: Long,
        result: GpgAgentOperationResult<T>,
        deniedMessage: String,
        onSuccess: (T) -> GpgAgentMessages.IpcResponse,
    ): GpgAgentMessages.IpcResponse =
        when (result) {
            is GpgAgentOperationResult.Success -> onSuccess(result.response)

            GpgAgentOperationResult.VaultLocked -> errorResponse(
                requestId = requestId,
                message = "Vault is locked",
                code = AgentIpcMessages.ErrorCode.VAULT_LOCKED,
            )

            GpgAgentOperationResult.UserDenied -> errorResponse(
                requestId = requestId,
                message = deniedMessage,
                code = AgentIpcMessages.ErrorCode.USER_DENIED,
            )

            GpgAgentOperationResult.KeyNotFound -> errorResponse(
                requestId = requestId,
                message = "Key not found for the requested keygrip",
                code = AgentIpcMessages.ErrorCode.KEY_NOT_FOUND,
            )

            GpgAgentOperationResult.Unsupported -> errorResponse(
                requestId = requestId,
                message = "GPG key algorithm is not supported by this Keyguard version",
                code = AgentIpcMessages.ErrorCode.UNSUPPORTED,
            )

            is GpgAgentOperationResult.Failure -> errorResponse(
                requestId = requestId,
                message = result.message,
                code = AgentIpcMessages.ErrorCode.UNSPECIFIED,
            )
        }

    private fun errorResponse(
        requestId: Long,
        message: String,
        code: Int,
    ) = GpgAgentMessages.IpcResponse(
        id = requestId,
        error = AgentIpcMessages.ErrorResponse(
            message = message,
            code = code,
        ),
    )
}
