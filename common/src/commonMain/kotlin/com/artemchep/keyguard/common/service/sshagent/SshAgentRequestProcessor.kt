package com.artemchep.keyguard.common.service.sshagent

/**
 * Everything the approval surface may show for a pending SSH signing
 * request.
 */
data class SshAgentApprovalPrompt(
    val caller: SshAgentMessages.CallerIdentity?,
    val keyName: String,
    val keyFingerprint: String,
    /**
     * Identity of the vault entry holding the key, when known. The
     * locked-vault path resolves it from the exposed catalog, so a key
     * that is missing from the catalog carries no identity.
     */
    val accountId: String?,
    val cipherId: String?,
)

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
