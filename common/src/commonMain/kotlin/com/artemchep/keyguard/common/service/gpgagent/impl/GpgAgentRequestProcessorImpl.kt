package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.agent.flowBackedAgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.toApprovalCacheIdentity
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyNotFoundException
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentUnsupportedAlgorithmException
import com.artemchep.keyguard.common.service.gpgagent.hasPrivateKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.gpgagent.toGpgPublicKeyEntry
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.enqueueEvent
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class GpgAgentRequestProcessorImpl(
    private val logRepository: LogRepository,
    private val crypto: GpgAgentCrypto,
    private val getVaultSession: GetVaultSession,
    getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    getGpgAgentApprovalCachePolicy: GetGpgAgentApprovalCachePolicy =
        GetGpgAgentApprovalCachePolicyNoOp,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    scope: CoroutineScope,
    private val gpgPublicKeyRepository: GpgPublicKeyRepository = GpgPublicKeyRepositoryEmpty,
    private val pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
    private val sessionId: String = "",
    private val json: Json = Json,
    private val onApprovalRequest: suspend (GpgAgentApprovalPrompt) -> Boolean = { true },
) : GpgAgentRequestProcessor {
    companion object {
        private const val TAG = "GpgAgentRequestProcessor"

        internal const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    private val gpgAgentFilterState = getGpgAgentFilter()
        .stateIn(scope, SharingStarted.Eagerly, GpgAgentFilter())

    private val approvalCacheConfig = getGpgAgentApprovalCachePolicy.approvalCacheConfig
        ?: flowBackedAgentApprovalCacheConfigProvider(
            approvalWindow = getGpgAgentApprovalWindow(),
            cachePolicy = getGpgAgentApprovalCachePolicy(),
            scope = scope,
        )

    private val approvalWindowMemory =
        AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = getVaultSession,
            scope = scope,
        )

    override suspend fun listKeys(
        caller: GpgAgentMessages.CallerIdentity?,
    ): GpgAgentRequestProcessor.ListKeysResult {
        // The catalog and the vault intentionally keep one entry per
        // (cipher, keygrip) pair, while KEYINFO lists each keygrip once —
        // both listing paths collapse the cipher dimension here.
        val vault = getGpgKeysFromVault()
        if (vault == null) {
            val keys = getCachedGpgKeys()
                .map { it.toGpgKeyMessage() }
                .distinctBy { it.keygrip }
            recordPendingGpgUsage(
                cipherId = null,
                caller = caller,
                request = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
                response = GpgUsageHistoryResponseType.SUCCESS,
                fingerprint = null,
                keygrip = null,
                // gpg runs KEYINFO on most operations; while the vault
                // stays locked all probes from the same program collapse
                // into a single queued event so that they can not push
                // rarer denial events past the queue cap.
                coalescenceKey = "OPENPGP|AGENT_LIST_KEYS|${caller?.processName.orEmpty()}",
            )
            return GpgAgentRequestProcessor.ListKeysResult.Success(
                response = GpgAgentMessages.ListKeysResponse(keys = keys),
            )
        }

        val keys = vault.gpgSecrets
            .flatMap { secret ->
                secret.toGpgKeyMessages()
            }
            .distinctBy { it.keygrip }
        recordGpgUsage(
            vault = vault,
            cipherId = null,
            caller = caller,
            request = GpgUsageHistoryRequestType.AGENT_LIST_KEYS,
            response = GpgUsageHistoryResponseType.SUCCESS,
            fingerprint = null,
            keygrip = null,
        )

        return GpgAgentRequestProcessor.ListKeysResult.Success(
            response = GpgAgentMessages.ListKeysResponse(keys = keys),
        )
    }

    override suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.SignHashResponse> = runKeyOperation(
        operation = GpgAgentOperation.SIGN,
        usageRequestType = GpgUsageHistoryRequestType.AGENT_SIGN_HASH,
        keygrip = request.keygrip,
        caller = request.caller,
        logNoun = "signing",
        findKey = { keygrip -> findKeyByKeygrip(keygrip) { it.canSign } },
        crypto = { match ->
            crypto.signHash(
                privateKeyArmored = match.secret.privateKeyArmored
                    ?: throw GpgAgentKeyNotFoundException(),
                metadataKey = match.metadataKey,
                hashAlgorithm = request.hashAlgorithm,
                hash = request.hash,
            )
        },
    )

    override suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse> = runKeyOperation(
        operation = GpgAgentOperation.DECRYPT,
        usageRequestType = GpgUsageHistoryRequestType.AGENT_DECRYPT,
        keygrip = request.keygrip,
        caller = request.caller,
        logNoun = "decryption",
        findKey = { keygrip -> findKeyByKeygrip(keygrip) { it.canDecrypt } },
        crypto = { match ->
            crypto.pkdecrypt(
                privateKeyArmored = match.secret.privateKeyArmored
                    ?: throw GpgAgentKeyNotFoundException(),
                metadataKey = match.metadataKey,
                ciphertext = request.ciphertext,
                unwrapEcdh = request.unwrapEcdh,
            )
        },
    )

    /**
     * Shared skeleton for the two vault-backed key operations (sign / decrypt). The two
     * public entry points supply the operation-specific deltas — the operation enum, the
     * usage-history request type, the key finder predicate, the crypto call and the log
     * noun — while this function owns the identical approval / vault / usage-recording flow.
     */
    private suspend fun <T> runKeyOperation(
        operation: GpgAgentOperation,
        usageRequestType: GpgUsageHistoryRequestType,
        keygrip: String,
        caller: GpgAgentMessages.CallerIdentity?,
        logNoun: String,
        findKey: GpgVaultContext.(String) -> GpgKeyMatch?,
        crypto: (GpgKeyMatch) -> T,
    ): GpgAgentOperationResult<T> {
        val normalizedKeygrip = keygrip.normalizeGpgKeygrip()
        var vault = getGpgKeysFromVault()
        val wasVaultLocked = vault == null
        if (wasVaultLocked) {
            approvalWindowMemory.clearSession()
        }

        var approvalAccess = vault?.approvalWindowSession
            ?.access(
                operation = operation,
                keygrip = normalizedKeygrip,
                caller = caller,
            )
        val approvalRemembered = approvalAccess?.isRemembered == true
        var approvalGranted = false

        if (wasVaultLocked) {
            val cachedKey = getCachedGpgKey(normalizedKeygrip)
            val approved = requestApproval(
                GpgAgentApprovalPrompt(
                    operation = operation,
                    caller = caller,
                    keyName = cachedKey?.displayName ?: "GPG key",
                    keyFingerprint = cachedKey?.fingerprint.orEmpty(),
                    keygrip = normalizedKeygrip,
                    accountId = cachedKey?.accountId,
                    cipherId = cachedKey?.cipherId,
                ),
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the GPG $logNoun request while vault was locked", LogLevel.INFO)
                recordPendingGpgUsage(
                    cipherId = cachedKey?.cipherId,
                    caller = caller,
                    request = usageRequestType,
                    response = GpgUsageHistoryResponseType.USER_DENIED,
                    fingerprint = cachedKey?.fingerprint,
                    keygrip = normalizedKeygrip,
                )
                return GpgAgentOperationResult.UserDenied
            }
            approvalGranted = true

            vault = getGpgKeysFromVault()
            if (vault == null) {
                recordPendingGpgUsage(
                    cipherId = cachedKey?.cipherId,
                    caller = caller,
                    request = usageRequestType,
                    response = GpgUsageHistoryResponseType.VAULT_LOCKED,
                    fingerprint = cachedKey?.fingerprint,
                    keygrip = normalizedKeygrip,
                )
                return GpgAgentOperationResult.VaultLocked
            }
            approvalAccess = vault.approvalWindowSession.access(
                operation = operation,
                keygrip = normalizedKeygrip,
                caller = caller,
            )
        }

        val match = vault.findKey(normalizedKeygrip)
            ?: run {
                recordGpgUsage(
                    vault = vault,
                    cipherId = null,
                    caller = caller,
                    request = usageRequestType,
                    response = GpgUsageHistoryResponseType.KEY_NOT_FOUND,
                    fingerprint = null,
                    keygrip = normalizedKeygrip,
                )
                return GpgAgentOperationResult.KeyNotFound
            }

        // Record the GPG usage for this
        // specific key operation request.
        suspend fun recordGpgUsageForOperation(
            response: GpgUsageHistoryResponseType,
        ) = recordGpgUsage(
            vault = vault,
            cipherId = match.secret.cipher.id,
            caller = caller,
            request = usageRequestType,
            response = response,
            fingerprint = match.metadataKey.fingerprint.ifBlank {
                match.secret.fingerprint.orEmpty()
            },
            keygrip = normalizedKeygrip,
        )

        if (!wasVaultLocked && !approvalRemembered) {
            val approved = requestApproval(
                GpgAgentApprovalPrompt(
                    operation = operation,
                    caller = caller,
                    keyName = match.secret.cipher.name,
                    keyFingerprint = match.metadataKey.fingerprint.ifBlank {
                        match.secret.fingerprint.orEmpty()
                    },
                    keygrip = normalizedKeygrip,
                    accountId = match.secret.cipher.accountId,
                    cipherId = match.secret.cipher.id,
                ),
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the GPG $logNoun request", LogLevel.INFO)
                recordGpgUsageForOperation(GpgUsageHistoryResponseType.USER_DENIED)
                return GpgAgentOperationResult.UserDenied
            }
            approvalGranted = true
        }

        return try {
            val response = crypto(match)
            if (approvalGranted) {
                approvalAccess?.remember()
            }
            recordGpgUsageForOperation(GpgUsageHistoryResponseType.SUCCESS)
            GpgAgentOperationResult.Success(response = response)
        } catch (_: GpgAgentKeyNotFoundException) {
            recordGpgUsageForOperation(GpgUsageHistoryResponseType.KEY_NOT_FOUND)
            GpgAgentOperationResult.KeyNotFound
        } catch (e: GpgAgentUnsupportedAlgorithmException) {
            logRepository.post(TAG, e.message.orEmpty(), LogLevel.WARNING)
            recordGpgUsageForOperation(GpgUsageHistoryResponseType.UNSUPPORTED)
            GpgAgentOperationResult.Unsupported
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "GPG $logNoun failed: ${e.message}", LogLevel.ERROR)
            recordGpgUsageForOperation(GpgUsageHistoryResponseType.FAILURE)
            GpgAgentOperationResult.Failure(
                message = "GPG $logNoun failed: ${e.message}",
            )
        }
    }

    private suspend fun getCachedGpgKeys(): List<GpgAgentKeyInfoRow> = try {
        gpgPublicKeyRepository.getKeyInfo()
            .bind()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached GPG public keys: ${e.message}", LogLevel.ERROR)
        emptyList()
    }

    private suspend fun getCachedGpgKey(
        keygrip: String,
    ): GpgAgentKeyInfoRow? = try {
        // The same component key may live in more than one cipher; for
        // the approval display any of the matching rows works, so take
        // the first one of the deterministically ordered result.
        gpgPublicKeyRepository.getKeyInfoByKeygrip(keygrip)
            .bind()
            .firstOrNull()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached GPG public key: ${e.message}", LogLevel.ERROR)
        null
    }

    private fun GpgAgentKeyInfoRow.toGpgKeyMessage() = GpgAgentMessages.GpgKey(
        name = displayName,
        keygrip = keygrip,
        fingerprint = fingerprint,
        algorithm = algorithm,
        canSign = canSign,
        canDecrypt = canDecrypt,
    )

    private fun GpgAgentSecret.toGpgKeyMessages(): List<GpgAgentMessages.GpgKey> =
        toGpgPublicKeyEntry(name = cipher.name)
            .keyInfo
            .map { key ->
                GpgAgentMessages.GpgKey(
                    name = cipher.name,
                    keygrip = key.keygrip,
                    fingerprint = key.fingerprint,
                    algorithm = key.algorithm,
                    canSign = key.canSign,
                    canDecrypt = key.canDecrypt,
                )
            }

    private suspend fun getGpgKeysFromVault(): GpgVaultContext? {
        val session = getVaultSession.valueOrNull
        val key = session as? MasterSession.Key ?: return null
        val approvalWindowSession = approvalWindowMemory.getOrGenerateSession(key)

        val getCiphers = key.di.direct.instance<GetCiphers>()
        val gpgSecrets = getCiphers()
            .first()
            .mapNotNull { it.toGpgAgentSecretOrNull() }
        val addGpgUsageHistory = key.di.direct.instanceOrNull<AddGpgUsageHistory>()
            ?: NoOpAddGpgUsageHistory

        return GpgVaultContext(
            gpgSecrets = gpgAgentFilterState.value.filterCiphers(
                directDI = key.di.direct,
                items = gpgSecrets,
                cipherOf = { it.cipher },
            ),
            addGpgUsageHistory = addGpgUsageHistory,
            approvalWindowSession = approvalWindowSession,
        )
    }

    /**
     * Records an event that happened while the vault was locked: the
     * usage-history tables are unreachable, so the event is sealed into
     * the pending queue and flushed on the next unlock. A no-op when no
     * queue is wired up (e.g. tests, embedded uses).
     */
    @Suppress("LongParameterList")
    private suspend fun recordPendingGpgUsage(
        cipherId: String?,
        caller: GpgAgentMessages.CallerIdentity?,
        request: GpgUsageHistoryRequestType,
        response: GpgUsageHistoryResponseType,
        fingerprint: String?,
        keygrip: String?,
        coalescenceKey: String? = null,
    ) {
        val queue = pendingUsageHistoryQueue ?: return
        try {
            queue.enqueueEvent(
                protocol = PendingUsageHistory.Protocol.OPENPGP,
                sessionId = sessionId,
                caller = encodeCaller(caller),
                requestType = request.name,
                responseType = response.name,
                cipherId = cipherId,
                fingerprint = fingerprint,
                keygrip = keygrip,
                coalescenceKey = coalescenceKey,
            ).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Failed to queue GPG usage history: ${e.message}", LogLevel.ERROR)
        }
    }

    private suspend fun recordGpgUsage(
        vault: GpgVaultContext,
        cipherId: String?,
        caller: GpgAgentMessages.CallerIdentity?,
        request: GpgUsageHistoryRequestType,
        response: GpgUsageHistoryResponseType,
        fingerprint: String?,
        keygrip: String?,
    ) {
        val callerJson = encodeCaller(caller)
        try {
            val request = AddGpgUsageHistoryRequest(
                cipherId = cipherId,
                sessionId = sessionId,
                caller = callerJson,
                request = request,
                response = response,
                fingerprint = fingerprint,
                keygrip = keygrip,
            )
            vault.addGpgUsageHistory(request).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Failed to record GPG usage history: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun encodeCaller(
        caller: GpgAgentMessages.CallerIdentity?,
    ): String? {
        caller ?: return null
        return runCatching {
            json.encodeToString(caller)
        }.getOrNull()
    }

    private suspend fun requestApproval(
        prompt: GpgAgentApprovalPrompt,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApprovalRequest(prompt)
        } ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logRepository.post(TAG, "GPG approval request failed: ${e.message}", LogLevel.ERROR)
        false
    }

    private fun GpgVaultContext.findKeyByKeygrip(
        keygrip: String,
        predicate: (GpgAgentKeyMetadataKey) -> Boolean,
    ): GpgKeyMatch? = gpgSecrets
        .firstNotNullOfOrNull { secret ->
            if (!secret.hasPrivateKey) {
                return@firstNotNullOfOrNull null
            }
            secret.metadata.keys
                .firstOrNull { key ->
                    predicate(key) && key.keygrip.normalizeGpgKeygrip() == keygrip
                }
                ?.let { metadataKey ->
                    GpgKeyMatch(
                        secret = secret,
                        metadataKey = metadataKey,
                    )
                }
        }

    private data class GpgVaultContext(
        val gpgSecrets: List<GpgAgentSecret>,
        val addGpgUsageHistory: AddGpgUsageHistory,
        val approvalWindowSession: AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>.Session,
    )

    private data class GpgKeyMatch(
        val secret: GpgAgentSecret,
        val metadataKey: GpgAgentKeyMetadataKey,
    )

    private object NoOpAddGpgUsageHistory : AddGpgUsageHistory {
        override fun invoke(request: AddGpgUsageHistoryRequest): IO<Unit> = {
            // Do nothing
        }
    }
}

private suspend fun AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>.Session.access(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: GpgAgentMessages.CallerIdentity?,
): AgentApprovalWindowMemory<GpgApprovalCacheKey, AgentApprovalCachePolicy>.Access = access { policy ->
    gpgApprovalCacheKey(operation, keygrip, caller, policy)
}

internal fun gpgApprovalCacheKey(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: GpgAgentMessages.CallerIdentity?,
    policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
): GpgApprovalCacheKey? {
    val callerIdentity = caller.toApprovalCacheIdentity(policy)
        ?: return null
    return GpgApprovalCacheKey(
        operation = operation,
        // The keygrip is already normalized by the caller (runKeyOperation); normalizing
        // once there keeps a single source of truth for the cache-key identity.
        keygrip = keygrip,
        callerIdentity = callerIdentity,
    )
}

internal data class GpgApprovalCacheKey(
    val operation: GpgAgentOperation,
    val keygrip: String,
    val callerIdentity: AgentApprovalCacheIdentity,
)
