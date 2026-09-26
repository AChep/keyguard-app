package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.enqueueEvent
import com.artemchep.keyguard.common.service.session.SshAgentSessionAccess
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

class SshAgentRequestProcessorImpl(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: SshAgentSessionAccess,
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
        var session = getVaultSession.valueOrNull as? MasterSession.Key
        val wasVaultLocked = session == null
        if (wasVaultLocked) {
            approvalWindowMemory.clearSession()
        }

        var approvalGranted = false
        val cachedKey = if (wasVaultLocked) getCachedSshKey(request.publicKey) else null

        if (wasVaultLocked) {
            logRepository.post(TAG, "Vault is locked, requesting approval before SSH signing", LogLevel.INFO)
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

            session = getVaultSession.valueOrNull as? MasterSession.Key
        }

        // Cache access can suspend while settings are persisted. Resolve the
        // keys afterward so even remembered approvals use current eligibility.
        val approvalSession = session?.let { key ->
            approvalWindowMemory.getOrGenerateSession(key)
        }
        var approvalAccess = approvalSession?.access(request)
        val vault = getSshKeysFromVault(session)
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

        val matchingSecret = vault.findKeyByPublicKey(request.publicKey) ?: run {
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

        // Record the SSH usage when the vault is no
        // longer available to store it.
        suspend fun recordPendingVaultLockedSignData() = recordPendingSshUsage(
            cipherId = matchingSecret.id,
            caller = request.caller,
            request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
            response = SshUsageHistoryResponseType.VAULT_LOCKED,
            fingerprint = matchingSecret.sshKey?.fingerprint,
        )

        val sshKey = matchingSecret.sshKey ?: return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        if (sshKey.privateKey.isNullOrBlank()) {
            recordSshUsageSignData(SshUsageHistoryResponseType.KEY_NOT_FOUND)
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }

        val requiresApproval = !wasVaultLocked && approvalAccess?.canReuseNow() != true
        if (requiresApproval) {
            if (approvalAccess?.isRemembered == true) {
                // Bind the new prompt to the current policy. Any wait here is
                // followed by approval and another key/filter read below.
                approvalAccess = approvalSession?.access(request)
            }
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

        // Approval can remain on screen while the vault locks, its session is
        // replaced, or the key/filter changes. Never use the captured secret
        // after that suspension without resolving its current eligibility.
        val currentVault = if (requiresApproval) getSshKeysFromVault(vault.session) else vault
        if (currentVault == null) {
            recordPendingVaultLockedSignData()
            return SshAgentRequestProcessor.SignDataResult.VaultLocked
        }
        val currentSecret = if (requiresApproval) {
            currentVault.restrictTo(matchingSecret).findKeyByPublicKey(request.publicKey)
        } else {
            matchingSecret
        }
        val currentSshKey = currentSecret?.sshKey
        val privateKeyPem = currentSshKey?.privateKey
        if (currentSshKey == null || privateKeyPem.isNullOrBlank()) {
            recordSshUsageSignData(SshUsageHistoryResponseType.KEY_NOT_FOUND)
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }
        currentCoroutineContext().ensureActive()
        // Check as close to the synchronous native call as possible. Locking
        // cannot revoke work that is already executing inside crypto.
        if (getVaultSession.valueOrNull !== currentVault.session) {
            recordPendingVaultLockedSignData()
            return SshAgentRequestProcessor.SignDataResult.VaultLocked
        }

        return try {
            val signature = NativeCrypto.ssh.sign(
                privateKeyPem = privateKeyPem,
                publicKeyOpenSsh = currentSshKey.publicKey,
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

    // Guard clauses reject unsupported or stale input before accessing the active session.
    @Suppress("ReturnCount")
    private suspend fun getSshKeysFromVault(
        session: MasterSession.Key? = getVaultSession.valueOrNull as? MasterSession.Key,
    ): SshVaultContext? {
        val key = session ?: return null
        if (getVaultSession.valueOrNull !== key || !key.session.active.value) return null

        val dependencies = sessionAccess(key) ?: return null
        val getCiphers = dependencies.getCiphers
        val sshKeys = getCiphers()
            .map { ciphers ->
                ciphers.filter { it.isEligibleForSshAgent() }
            }
            .first()
        val addSshUsageHistory = dependencies.addSshUsageHistory
            ?: NoOpAddSshUsageHistory

        val filteredKeys = getSshAgentFilter().first().filterCiphers(
            context = dependencies.filterContext,
            ciphers = sshKeys,
        )
        if (getVaultSession.valueOrNull !== key || !key.session.active.value) return null
        return SshVaultContext(
            session = key,
            sshKeys = filteredKeys,
            addSshUsageHistory = addSshUsageHistory,
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
        val session: MasterSession.Key,
        val sshKeys: List<DSecret>,
        val addSshUsageHistory: AddSshUsageHistory,
    ) {
        fun findKeyByPublicKey(publicKey: String): DSecret? = sshKeys
            .firstOrNull { secret ->
                val candidate = secret.sshKey?.publicKey ?: return@firstOrNull false
                sshPublicKeysMatch(candidate, publicKey)
            }

        /** Keeps only the keys that belong to the given cipher. */
        fun restrictTo(cipher: DSecret) = copy(
            sshKeys = sshKeys.filter {
                it.id == cipher.id && it.accountId == cipher.accountId
            },
        )
    }

    private object NoOpAddSshUsageHistory : AddSshUsageHistory {
        override fun invoke(request: AddSshUsageHistoryRequest): IO<Unit> = {
            // Do nothing
        }
    }
}
