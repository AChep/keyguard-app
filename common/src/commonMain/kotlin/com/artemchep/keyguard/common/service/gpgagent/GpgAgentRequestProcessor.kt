package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlin.time.Instant

/**
 * Everything the approval surface may show for a pending GPG key
 * operation request.
 */
data class GpgAgentApprovalPrompt(
    val operation: GpgAgentOperation,
    val caller: CallerIdentity?,
    val keyName: String,
    val keyFingerprint: String,
    val keygrip: String,
    /**
     * Identity of the vault entry holding the key, when known. The
     * locked-vault path resolves it from the exposed catalog, so a key
     * that is missing from the catalog carries no identity.
     */
    val accountId: String?,
    val cipherId: String?,
    /**
     * When the prompt is denied. The prompts of one request share it, so
     * the request ends within one approval timeout however many it shows.
     */
    val expiresAt: Instant,
)

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
        caller: CallerIdentity?,
    ): ListKeysResult

    suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.SignHashResponse>

    suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse>
}
