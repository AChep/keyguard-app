package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult

data class GpgAgentRpcRequestContext(
    val authenticated: Boolean,
    val allowAuthenticate: Boolean,
)

internal class GpgAgentRpcHandler(
    private val requestProcessor: GpgAgentRequestProcessor,
    private val authenticate: (GpgAgentMessages.AuthenticateRequest) -> Boolean = { false },
) {
    suspend fun processRequest(
        request: GpgAgentMessages.IpcRequest,
        context: GpgAgentRpcRequestContext,
    ): GpgAgentMessages.IpcResponse {
        val requestVariantCount =
            (if (request.authenticate != null) 1 else 0) +
                    (if (request.listKeys != null) 1 else 0) +
                    (if (request.signHash != null) 1 else 0) +
                    (if (request.pkdecrypt != null) 1 else 0)
        if (requestVariantCount > 1) {
            return errorResponse(
                requestId = request.id,
                message = "Malformed request: multiple request variants set",
                code = GpgAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

        if (!context.allowAuthenticate && request.authenticate != null) {
            return errorResponse(
                requestId = request.id,
                message = "AuthenticateRequest is not supported on this transport",
                code = GpgAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

        if (!context.authenticated && request.authenticate == null) {
            return errorResponse(
                requestId = request.id,
                message = "Not authenticated. Send AuthenticateRequest first.",
                code = GpgAgentMessages.ErrorCode.NOT_AUTHENTICATED,
            )
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
                code = GpgAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }
    }

    fun handleAuthenticate(
        requestId: Long,
        request: GpgAgentMessages.AuthenticateRequest,
    ): GpgAgentMessages.IpcResponse = GpgAgentMessages.IpcResponse(
        id = requestId,
        authenticate = GpgAgentMessages.AuthenticateResponse(
            success = authenticate(request),
            protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
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
                code = GpgAgentMessages.ErrorCode.VAULT_LOCKED,
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
                code = GpgAgentMessages.ErrorCode.VAULT_LOCKED,
            )

            GpgAgentOperationResult.UserDenied -> errorResponse(
                requestId = requestId,
                message = deniedMessage,
                code = GpgAgentMessages.ErrorCode.USER_DENIED,
            )

            GpgAgentOperationResult.KeyNotFound -> errorResponse(
                requestId = requestId,
                message = "Key not found for the requested keygrip",
                code = GpgAgentMessages.ErrorCode.KEY_NOT_FOUND,
            )

            GpgAgentOperationResult.Unsupported -> errorResponse(
                requestId = requestId,
                message = "GPG key algorithm is not supported by this Keyguard version",
                code = GpgAgentMessages.ErrorCode.UNSUPPORTED,
            )

            is GpgAgentOperationResult.Failure -> errorResponse(
                requestId = requestId,
                message = result.message,
                code = GpgAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

    private fun errorResponse(
        requestId: Long,
        message: String,
        code: Int,
    ) = GpgAgentMessages.IpcResponse(
        id = requestId,
        error = GpgAgentMessages.ErrorResponse(
            message = message,
            code = code,
        ),
    )
}
