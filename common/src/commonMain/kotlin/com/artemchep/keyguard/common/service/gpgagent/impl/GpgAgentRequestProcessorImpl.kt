package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyNotFoundException
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentUnsupportedAlgorithmException
import com.artemchep.keyguard.common.service.gpgagent.hasPrivateKey
import com.artemchep.keyguard.common.service.gpgagent.isUsableAgentKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgKeygrip
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
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
    private val getGpgAgentFilter: GetGpgAgentFilter,
    scope: CoroutineScope,
    private val gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository = GpgAgentPublicKeyRepositoryEmpty,
    private val sessionId: String = "",
    private val json: Json = Json,
    private val onApprovalRequest: suspend (
        operation: GpgAgentOperation,
        caller: GpgAgentMessages.CallerIdentity?,
        keyName: String,
        keyFingerprint: String,
        keygrip: String,
    ) -> Boolean = { _, _, _, _, _ -> true },
) : GpgAgentRequestProcessor {
    companion object {
        private const val TAG = "GpgAgentRequestProcessor"

        internal const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    private val gpgAgentFilterState = getGpgAgentFilter()
        .stateIn(scope, SharingStarted.Eagerly, GpgAgentFilter())

    private val approvalWindowMemory = AgentApprovalWindowMemory<GpgApprovalCacheKey>(
        approvalWindow = getGpgAgentApprovalWindow(),
        getVaultSession = getVaultSession,
        scope = scope,
    )

    override suspend fun listKeys(
        caller: GpgAgentMessages.CallerIdentity?,
    ): GpgAgentRequestProcessor.ListKeysResult {
        val vault = getGpgKeysFromVault()
        if (vault == null) {
            val keys = getCachedGpgKeys()
                .map { it.toGpgKeyMessage() }
            return GpgAgentRequestProcessor.ListKeysResult.Success(
                response = GpgAgentMessages.ListKeysResponse(keys = keys),
            )
        }

        val keys = vault.gpgSecrets.flatMap { secret ->
            secret.toGpgKeyMessages()
        }
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

        val approvalRemembered = vault?.approvalWindowSession
            ?.isRemembered(operation, normalizedKeygrip, caller) == true
        var approvalGranted = false

        if (wasVaultLocked) {
            val cachedKey = getCachedGpgKey(normalizedKeygrip)
            val approved = requestApproval(
                operation = operation,
                keyName = cachedKey?.displayName ?: "GPG key",
                keyFingerprint = cachedKey?.fingerprint.orEmpty(),
                keygrip = normalizedKeygrip,
                caller = caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the GPG $logNoun request while vault was locked", LogLevel.INFO)
                return GpgAgentOperationResult.UserDenied
            }
            approvalGranted = true

            vault = getGpgKeysFromVault()
            if (vault == null) {
                return GpgAgentOperationResult.VaultLocked
            }
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
                operation = operation,
                keyName = match.secret.cipher.name,
                keyFingerprint = match.metadataKey.fingerprint.ifBlank {
                    match.secret.fingerprint.orEmpty()
                },
                keygrip = normalizedKeygrip,
                caller = caller,
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
                vault.approvalWindowSession.remember(operation, normalizedKeygrip, caller)
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

    private suspend fun getCachedGpgKeys(): List<GpgAgentPublicKeyRow> = try {
        gpgAgentPublicKeyRepository.get()
            .bind()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached GPG public keys: ${e.message}", LogLevel.ERROR)
        emptyList()
    }

    private suspend fun getCachedGpgKey(
        keygrip: String,
    ): GpgAgentPublicKeyRow? = try {
        gpgAgentPublicKeyRepository.getByKeygrip(keygrip)
            .bind()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached GPG public key: ${e.message}", LogLevel.ERROR)
        null
    }

    private fun GpgAgentPublicKeyRow.toGpgKeyMessage() = GpgAgentMessages.GpgKey(
        name = displayName,
        keygrip = keygrip,
        fingerprint = fingerprint,
        algorithm = algorithm,
        canSign = canSign,
        canDecrypt = canDecrypt,
    )

    private fun GpgAgentSecret.toGpgKeyMessages(): List<GpgAgentMessages.GpgKey> =
        metadata.keys
            .filter { it.isUsableAgentKey }
            .map { key ->
                GpgAgentMessages.GpgKey(
                    name = cipher.name,
                    keygrip = key.keygrip.normalizeGpgKeygrip(),
                    fingerprint = key.fingerprint.ifBlank { fingerprint.orEmpty() },
                    algorithm = key.algorithm,
                    canSign = hasPrivateKey && key.canSign,
                    canDecrypt = hasPrivateKey && key.canDecrypt,
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

        val gpgAgentFilter = gpgAgentFilterState.value.normalize()
        if (!gpgAgentFilter.isActive) {
            return GpgVaultContext(
                gpgSecrets = gpgSecrets,
                addGpgUsageHistory = addGpgUsageHistory,
                approvalWindowSession = approvalWindowSession,
            )
        }

        val ciphers = gpgSecrets.map { it.cipher }
        val predicate = gpgAgentFilter.toDFilter().prepare(
            directDI = key.di.direct,
            ciphers = ciphers,
        )
        return GpgVaultContext(
            gpgSecrets = gpgSecrets.filter { predicate(it.cipher) },
            addGpgUsageHistory = addGpgUsageHistory,
            approvalWindowSession = approvalWindowSession,
        )
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
        operation: GpgAgentOperation,
        keyName: String,
        keyFingerprint: String,
        keygrip: String,
        caller: GpgAgentMessages.CallerIdentity?,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApprovalRequest(
                operation,
                caller,
                keyName,
                keyFingerprint,
                keygrip,
            )
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
        val approvalWindowSession: AgentApprovalWindowMemory<GpgApprovalCacheKey>.Session,
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

private suspend fun AgentApprovalWindowMemory<GpgApprovalCacheKey>.Session.isRemembered(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: GpgAgentMessages.CallerIdentity?,
): Boolean = isRemembered(gpgApprovalCacheKey(operation, keygrip, caller))

private suspend fun AgentApprovalWindowMemory<GpgApprovalCacheKey>.Session.remember(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: GpgAgentMessages.CallerIdentity?,
) {
    remember(gpgApprovalCacheKey(operation, keygrip, caller))
}

private fun gpgApprovalCacheKey(
    operation: GpgAgentOperation,
    keygrip: String,
    caller: GpgAgentMessages.CallerIdentity?,
) = GpgApprovalCacheKey(
    operation = operation,
    // The keygrip is already normalized by the caller (runKeyOperation); normalizing
    // once there keeps a single source of truth for the cache-key identity.
    keygrip = keygrip,
    callerToken = caller.toApprovalCacheToken(),
)

private fun GpgAgentMessages.CallerIdentity?.toApprovalCacheToken(): String =
    "generic-caller=${this?.appName.orEmpty()}"

private data class GpgApprovalCacheKey(
    val operation: GpgAgentOperation,
    val keygrip: String,
    val callerToken: String,
)
