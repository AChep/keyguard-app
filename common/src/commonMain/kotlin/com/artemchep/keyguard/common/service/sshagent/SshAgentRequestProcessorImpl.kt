package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.enqueueEvent
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicyNoOp
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class SshAgentRequestProcessorImpl(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy =
        GetSshAgentApprovalCachePolicyNoOp,
    private val getSshAgentFilter: GetSshAgentFilter,
    scope: CoroutineScope,
    private val approvalWindowMemory: SshAgentApprovalWindowMemory =
        SshAgentApprovalWindowMemory(
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getVaultSession = getVaultSession,
            scope = scope,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
        ),
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
    private val pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
    private val sessionId: String = "",
    private val json: Json = Json,
    private val onApprovalRequest: suspend (SshAgentApprovalPrompt) -> Boolean = { true },
    private val onGetListRequest: suspend (caller: SshAgentMessages.CallerIdentity?) -> Boolean = { _ -> false },
) : SshAgentRequestProcessor {
    companion object {
        private const val TAG = "SshAgentRequestProcessor"

        internal const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    private val sshAgentFilterState = getSshAgentFilter()
        .stateIn(scope, SharingStarted.Eagerly, SshAgentFilter())

    override suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult {
        val vault = getSshKeysFromVault()
        if (vault == null) {
            val keys = getCachedSshKeys()
                .map { it.toSshKeyMessage() }
            recordPendingSshUsage(
                cipherId = null,
                caller = caller,
                request = SshUsageHistoryRequestType.AGENT_LIST_KEYS,
                response = SshUsageHistoryResponseType.SUCCESS,
                fingerprint = null,
                // Agent clients list keys on every connection; while the
                // vault stays locked all probes from the same program
                // collapse into a single queued event so that they can
                // not push rarer denial events past the queue cap.
                coalescenceKey = "SSH|AGENT_LIST_KEYS|${caller?.processName.orEmpty()}",
            )
            return SshAgentRequestProcessor.ListKeysResult.Success(
                response = SshAgentMessages.ListKeysResponse(
                    keys = keys,
                ),
            )
        }

        val keys = vault.sshKeys.mapNotNull { secret ->
            val sshKey = secret.sshKey ?: return@mapNotNull null
            val publicKey = sshKey.publicKey ?: return@mapNotNull null
            val keyType = extractSshKeyType(publicKey) ?: "unknown"
            SshAgentMessages.SshKey(
                name = secret.name,
                publicKey = publicKey,
                keyType = keyType,
                fingerprint = sshKey.fingerprint.orEmpty(),
            )
        }
        recordSshUsage(
            vault = vault,
            cipherId = null,
            caller = caller,
            request = SshUsageHistoryRequestType.AGENT_LIST_KEYS,
            response = SshUsageHistoryResponseType.SUCCESS,
            fingerprint = null,
        )

        return SshAgentRequestProcessor.ListKeysResult.Success(
            response = SshAgentMessages.ListKeysResponse(
                keys = keys,
            ),
        )
    }

    override suspend fun signData(
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentRequestProcessor.SignDataResult {
        var vault = getSshKeysFromVault()
        val wasVaultLocked = vault == null
        if (wasVaultLocked) {
            approvalWindowMemory.clearSession()
        }

        var approvalAccess = vault?.approvalWindowSession?.access(request)
        val approvalRemembered = approvalAccess?.isRemembered == true
        var approvalGranted = false

        if (wasVaultLocked) {
            logRepository.post(TAG, "Vault is locked, requesting approval before SSH signing", LogLevel.INFO)
            val cachedKey = getCachedSshKey(request.publicKey)
            val approved = requestSigningApproval(
                SshAgentApprovalPrompt(
                    caller = request.caller,
                    keyName = cachedKey?.displayName ?: "SSH key",
                    keyFingerprint = cachedKey?.fingerprint.orEmpty(),
                    accountId = cachedKey?.accountId,
                    cipherId = cachedKey?.cipherId,
                ),
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request while vault was locked", LogLevel.INFO)
                recordPendingSshUsage(
                    cipherId = cachedKey?.cipherId,
                    caller = request.caller,
                    request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
                    response = SshUsageHistoryResponseType.USER_DENIED,
                    fingerprint = cachedKey?.fingerprint,
                )
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
            approvalGranted = true

            vault = getSshKeysFromVault()
            if (vault == null) {
                recordPendingSshUsage(
                    cipherId = cachedKey?.cipherId,
                    caller = request.caller,
                    request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
                    response = SshUsageHistoryResponseType.VAULT_LOCKED,
                    fingerprint = cachedKey?.fingerprint,
                )
                return SshAgentRequestProcessor.SignDataResult.VaultLocked
            }
            approvalAccess = vault.approvalWindowSession.access(request)
        }

        val availableSshKeys: List<DSecret> = vault.sshKeys
        val matchingSecret = availableSshKeys.find { secret ->
            val publicKey = secret.sshKey?.publicKey
                ?: return@find false
            sshPublicKeysMatch(publicKey, request.publicKey)
        } ?: run {
            recordSshUsage(
                vault = vault,
                cipherId = null,
                caller = request.caller,
                request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
                response = SshUsageHistoryResponseType.KEY_NOT_FOUND,
                fingerprint = null,
            )
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }

        // Record the SSH usage for this
        // specific sign data request.
        suspend fun recordSshUsageSignData(
            response: SshUsageHistoryResponseType,
        ) = recordSshUsage(
            vault = vault,
            cipherId = matchingSecret.id,
            caller = request.caller,
            request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
            response = response,
            fingerprint = matchingSecret.sshKey?.fingerprint,
        )

        val sshKey = matchingSecret.sshKey ?: return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        val privateKeyPem = sshKey.privateKey
        if (privateKeyPem.isNullOrBlank()) {
            recordSshUsageSignData(SshUsageHistoryResponseType.KEY_NOT_FOUND)
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }

        if (!wasVaultLocked && !approvalRemembered) {
            val approved = requestSigningApproval(
                SshAgentApprovalPrompt(
                    caller = request.caller,
                    keyName = matchingSecret.name,
                    keyFingerprint = sshKey.fingerprint ?: "",
                    accountId = matchingSecret.accountId,
                    cipherId = matchingSecret.id,
                ),
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request", LogLevel.INFO)
                recordSshUsageSignData(SshUsageHistoryResponseType.USER_DENIED)
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
            approvalGranted = true
        }

        return try {
            val signature = NativeCrypto.ssh.sign(
                privateKeyPem = privateKeyPem,
                publicKeyOpenSsh = sshKey.publicKey,
                data = request.data,
                flags = request.flags,
            )
            val response = SshAgentMessages.SignDataResponse(
                signature = signature.signature,
                algorithm = signature.algorithm,
            )
            if (approvalGranted) {
                approvalAccess?.remember()
            }
            recordSshUsageSignData(SshUsageHistoryResponseType.SUCCESS)
            SshAgentRequestProcessor.SignDataResult.Success(response = response)
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Signing failed: ${e.message}", LogLevel.ERROR)
            recordSshUsageSignData(SshUsageHistoryResponseType.FAILURE)
            SshAgentRequestProcessor.SignDataResult.Failure(
                message = "Signing failed: ${e.message}",
            )
        }
    }

    private suspend fun getCachedSshKeys(): List<SshAgentPublicKeyRow> = try {
        // The catalog is cipher-grained; the agent protocol lists each
        // distinct key once, so collapse ciphers sharing a public key.
        sshAgentPublicKeyRepository.get()
            .bind()
            .distinctBy { it.publicKeyBlobSha256 }
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached SSH public keys: ${e.message}", LogLevel.ERROR)
        emptyList()
    }

    private suspend fun getCachedSshKey(
        publicKey: String,
    ): SshAgentPublicKeyRow? = try {
        // The same key may live in more than one cipher; for the
        // approval display any of the matching rows works, so take
        // the first one of the deterministically ordered result.
        sshAgentPublicKeyRepository.getByPublicKey(publicKey)
            .bind()
            .firstOrNull()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached SSH public key: ${e.message}", LogLevel.ERROR)
        null
    }

    private fun SshAgentPublicKeyRow.toSshKeyMessage() = SshAgentMessages.SshKey(
        name = displayName,
        publicKey = publicKey,
        keyType = keyType,
        fingerprint = fingerprint,
    )

    private suspend fun getSshKeysFromVault(): SshVaultContext? {
        val session = getVaultSession.valueOrNull
        val key = session as? MasterSession.Key ?: return null
        val approvalWindowSession = approvalWindowMemory.getOrGenerateSession(key)

        val getCiphers = key.di.direct.instance<GetCiphers>()
        val sshKeys = getCiphers()
            .map { ciphers ->
                ciphers.filter { it.isEligibleForSshAgent() }
            }
            .first()
        val addSshUsageHistory = key.di.direct.instanceOrNull<AddSshUsageHistory>()
            ?: NoOpAddSshUsageHistory

        return SshVaultContext(
            sshKeys = sshAgentFilterState.value.filterCiphers(
                directDI = key.di.direct,
                ciphers = sshKeys,
            ),
            addSshUsageHistory = addSshUsageHistory,
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
    private suspend fun recordPendingSshUsage(
        cipherId: String?,
        caller: SshAgentMessages.CallerIdentity?,
        request: SshUsageHistoryRequestType,
        response: SshUsageHistoryResponseType,
        fingerprint: String?,
        coalescenceKey: String? = null,
    ) {
        val queue = pendingUsageHistoryQueue ?: return
        try {
            queue.enqueueEvent(
                protocol = PendingUsageHistory.Protocol.SSH,
                sessionId = sessionId,
                caller = encodeCaller(caller),
                requestType = request.name,
                responseType = response.name,
                cipherId = cipherId,
                fingerprint = fingerprint,
                coalescenceKey = coalescenceKey,
            ).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Failed to queue SSH usage history: ${e.message}", LogLevel.ERROR)
        }
    }

    private suspend fun recordSshUsage(
        vault: SshVaultContext,
        cipherId: String?,
        caller: SshAgentMessages.CallerIdentity?,
        request: SshUsageHistoryRequestType,
        response: SshUsageHistoryResponseType,
        fingerprint: String?,
    ) {
        val callerJson = encodeCaller(caller)
        try {
            val request = AddSshUsageHistoryRequest(
                cipherId = cipherId,
                sessionId = sessionId,
                caller = callerJson,
                request = request,
                response = response,
                fingerprint = fingerprint,
            )
            vault.addSshUsageHistory(request).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(TAG, "Failed to record SSH usage history: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun encodeCaller(
        caller: SshAgentMessages.CallerIdentity?,
    ): String? {
        caller ?: return null
        return runCatching {
            json.encodeToString(caller)
        }.getOrNull()
    }

    private suspend fun requestVaultUnlock(
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean {
        val unlocked = try {
            onGetListRequest(caller)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logRepository.post(TAG, "Unlock request failed: ${e.message}", LogLevel.ERROR)
            false
        }
        return unlocked
    }

    private suspend fun requestSigningApproval(
        prompt: SshAgentApprovalPrompt,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApprovalRequest(prompt)
        } ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logRepository.post(TAG, "Approval request failed: ${e.message}", LogLevel.ERROR)
        false
    }

    private data class SshVaultContext(
        val sshKeys: List<DSecret>,
        val addSshUsageHistory: AddSshUsageHistory,
        val approvalWindowSession: SshAgentApprovalWindowMemory.Session,
    )

    private object NoOpAddSshUsageHistory : AddSshUsageHistory {
        override fun invoke(request: AddSshUsageHistoryRequest): IO<Unit> = {
            // Do nothing
        }
    }
}
