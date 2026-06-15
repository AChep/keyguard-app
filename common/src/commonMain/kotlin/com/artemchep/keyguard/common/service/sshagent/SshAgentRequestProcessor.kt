package com.artemchep.keyguard.common.service.sshagent

interface SshAgentRequestProcessor {
    sealed interface ListKeysResult {
        data class Success(
            val response: SshAgentMessages.ListKeysResponse,
        ) : ListKeysResult

        data object VaultLocked : ListKeysResult
    }

    sealed interface SignDataResult {
        data class Success(
            val response: SshAgentMessages.SignDataResponse,
        ) : SignDataResult

        data object VaultLocked : SignDataResult

        data object UserDenied : SignDataResult

        data object KeyNotFound : SignDataResult

        data class Failure(
            val message: String,
        ) : SignDataResult
    }

    suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): ListKeysResult

    suspend fun signData(
        request: SshAgentMessages.SignDataRequest,
    ): SignDataResult
}
