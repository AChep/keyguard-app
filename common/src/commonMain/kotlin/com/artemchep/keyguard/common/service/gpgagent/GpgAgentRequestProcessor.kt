package com.artemchep.keyguard.common.service.gpgagent

interface GpgAgentRequestProcessor {
    sealed interface ListKeysResult {
        data class Success(
            val response: GpgAgentMessages.ListKeysResponse,
        ) : ListKeysResult

        data object VaultLocked : ListKeysResult
    }

    sealed interface GpgAgentOperationResult<out T> {
        data class Success<T>(
            val response: T,
        ) : GpgAgentOperationResult<T>

        data object VaultLocked : GpgAgentOperationResult<Nothing>

        data object UserDenied : GpgAgentOperationResult<Nothing>

        data object KeyNotFound : GpgAgentOperationResult<Nothing>

        data object Unsupported : GpgAgentOperationResult<Nothing>

        data class Failure(
            val message: String,
        ) : GpgAgentOperationResult<Nothing>
    }

    suspend fun listKeys(
        caller: GpgAgentMessages.CallerIdentity?,
    ): ListKeysResult

    suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.SignHashResponse>

    suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse>
}
