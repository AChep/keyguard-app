package com.artemchep.keyguard.common.service.sshagent

data class SshAgentRpcRequestContext(
    val authenticated: Boolean,
    val allowAuthenticate: Boolean,
    val callerAugmentation: SshAgentMessages.CallerIdentity? = null,
)

internal class SshAgentRpcHandler(
    private val requestProcessor: SshAgentRequestProcessor,
    private val authenticate: (SshAgentMessages.AuthenticateRequest) -> Boolean = { false },
) {
    suspend fun processRequest(
        request: SshAgentMessages.IpcRequest,
        context: SshAgentRpcRequestContext,
    ): SshAgentMessages.IpcResponse {
        val requestVariantCount =
            (if (request.authenticate != null) 1 else 0) +
                (if (request.listKeys != null) 1 else 0) +
                (if (request.signData != null) 1 else 0)
        if (requestVariantCount > 1) {
            return errorResponse(
                requestId = request.id,
                message = "Malformed request: multiple request variants set",
                code = SshAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

        if (!context.allowAuthenticate && request.authenticate != null) {
            return errorResponse(
                requestId = request.id,
                message = "AuthenticateRequest is not supported on this transport",
                code = SshAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

        if (!context.authenticated && request.authenticate == null) {
            return errorResponse(
                requestId = request.id,
                message = "Not authenticated. Send AuthenticateRequest first.",
                code = SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
            )
        }

        return when {
            request.authenticate != null -> handleAuthenticate(
                requestId = request.id,
                request = request.authenticate,
            )

            request.listKeys != null -> handleListKeys(
                requestId = request.id,
                request = request.listKeys.withAugmentedCaller(context.callerAugmentation),
            )

            request.signData != null -> handleSignData(
                requestId = request.id,
                request = request.signData.withAugmentedCaller(context.callerAugmentation),
            )

            else -> errorResponse(
                requestId = request.id,
                message = "Unknown request type",
                code = SshAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }
    }

    fun handleAuthenticate(
        requestId: Long,
        request: SshAgentMessages.AuthenticateRequest,
    ): SshAgentMessages.IpcResponse = SshAgentMessages.IpcResponse(
        id = requestId,
        authenticate = SshAgentMessages.AuthenticateResponse(success = authenticate(request)),
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
                code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
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
                code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
            )

            SshAgentRequestProcessor.SignDataResult.UserDenied -> errorResponse(
                requestId = requestId,
                message = "User denied the signing request",
                code = SshAgentMessages.ErrorCode.USER_DENIED,
            )

            SshAgentRequestProcessor.SignDataResult.KeyNotFound -> errorResponse(
                requestId = requestId,
                message = "Key not found for the requested public key",
                code = SshAgentMessages.ErrorCode.KEY_NOT_FOUND,
            )

            is SshAgentRequestProcessor.SignDataResult.Failure -> errorResponse(
                requestId = requestId,
                message = result.message,
                code = SshAgentMessages.ErrorCode.UNSPECIFIED,
            )
        }

    private fun errorResponse(
        requestId: Long,
        message: String,
        code: Int,
    ) = SshAgentMessages.IpcResponse(
        id = requestId,
        error = SshAgentMessages.ErrorResponse(
            message = message,
            code = code,
        ),
    )

    private fun SshAgentMessages.ListKeysRequest.withAugmentedCaller(
        callerAugmentation: SshAgentMessages.CallerIdentity?,
    ): SshAgentMessages.ListKeysRequest =
        copy(
            caller = mergeAndroidSshAgentCallerIdentity(
                caller = caller,
                senderAppInfo = callerAugmentation,
            ),
        )

    private fun SshAgentMessages.SignDataRequest.withAugmentedCaller(
        callerAugmentation: SshAgentMessages.CallerIdentity?,
    ): SshAgentMessages.SignDataRequest =
        copy(
            caller = mergeAndroidSshAgentCallerIdentity(
                caller = caller,
                senderAppInfo = callerAugmentation,
            ),
        )
}
