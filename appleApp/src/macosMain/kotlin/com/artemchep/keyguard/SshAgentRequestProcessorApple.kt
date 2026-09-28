package com.artemchep.keyguard

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.extractSshKeyType
import com.artemchep.keyguard.common.service.sshagent.isEligibleForSshAgent
import com.artemchep.keyguard.common.service.sshagent.sshPublicKeysMatch
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.apple.sshagent.SshAgentApprovalInfo
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

class SshAgentRequestProcessorApple(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getSshAgentApprovalCachePolicy: GetSshAgentApprovalCachePolicy,
    private val getSshAgentFilter: GetSshAgentFilter,
    scope: CoroutineScope,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
    private val sessionId: String,
    private val json: Json = Json,
    private val approvalWindowMemory: SshAgentApprovalWindowMemory =
        SshAgentApprovalWindowMemory(
            getSshAgentApprovalWindow = getSshAgentApprovalWindow,
            getSshAgentApprovalCachePolicy = getSshAgentApprovalCachePolicy,
            getVaultSession = getVaultSession,
            scope = scope,
        ),
    private val onApproval: suspend (SshAgentApprovalInfo) -> Boolean,
) : SshAgentRequestProcessor {
    companion object {
        private const val TAG = "SshAgentRequestProcessor"

        private const val APPROVAL_TIMEOUT_MS = 60_000L
    }

    override suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult {
        val vault = getSshKeysFromVault()
        if (vault == null) {
            val keys = getCachedSshKeys()
                .map { it.toSshKeyMessage() }
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
        val session = getVaultSession.valueOrNull as? MasterSession.Key
        if (session == null) {
            // Unlike the desktop, the macOS approval window has no unlock
            // affordance, so prompting while locked would be pointless.
            approvalWindowMemory.clearSession()
            return SshAgentRequestProcessor.SignDataResult.VaultLocked
        }

        // Cache access can suspend while settings are persisted. Resolve the
        // keys afterward so even remembered approvals use current eligibility.
        val approvalSession = approvalWindowMemory.getOrGenerateSession(session)
        var approvalAccess = approvalSession.access(request)
        val vault = getSshKeysFromVault(session)
            ?: return SshAgentRequestProcessor.SignDataResult.VaultLocked

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

        if (matchingSecret.sshKey?.privateKey.isNullOrBlank()) {
            recordSshUsageSignData(SshUsageHistoryResponseType.KEY_NOT_FOUND)
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }

        val requiresApproval = !approvalAccess.canReuseNow()
        if (requiresApproval) {
            if (approvalAccess.isRemembered) {
                // Bind the new prompt to the current policy. Any wait here is
                // followed by approval and another key/filter read below.
                approvalAccess = approvalSession.access(request)
            }
            val approved = requestSigningApproval(
                keyName = matchingSecret.name,
                keyFingerprint = matchingSecret.sshKey?.fingerprint.orEmpty(),
                caller = request.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request", LogLevel.INFO)
                recordSshUsageSignData(SshUsageHistoryResponseType.USER_DENIED)
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
        }

        val result = signWithCurrentKey(
            vault = vault,
            matchingSecret = matchingSecret,
            request = request,
            refreshKey = requiresApproval,
        )
        when (result) {
            is SshAgentRequestProcessor.SignDataResult.Success -> {
                if (requiresApproval) {
                    approvalAccess.remember()
                }
                recordSshUsageSignData(SshUsageHistoryResponseType.SUCCESS)
            }

            is SshAgentRequestProcessor.SignDataResult.Failure ->
                recordSshUsageSignData(SshUsageHistoryResponseType.FAILURE)
            else -> Unit
        }
        return result
    }

    private suspend fun signWithCurrentKey(
        vault: SshVaultContext,
        matchingSecret: DSecret,
        request: SshAgentMessages.SignDataRequest,
        refreshKey: Boolean,
    ): SshAgentRequestProcessor.SignDataResult {
        val currentVault = if (refreshKey) getSshKeysFromVault(vault.session) else vault
        if (currentVault == null || currentVault.session !== vault.session) {
            return SshAgentRequestProcessor.SignDataResult.VaultLocked
        }
        val currentSecret = currentVault.findKeyByPublicKey(request.publicKey, matchingSecret)
        val currentSshKey = currentSecret?.sshKey
        val currentPrivateKey = currentSshKey?.privateKey
        if (currentSshKey == null || currentPrivateKey.isNullOrBlank()) {
            recordSshUsage(
                vault = currentVault,
                cipherId = matchingSecret.id,
                caller = request.caller,
                request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
                response = SshUsageHistoryResponseType.KEY_NOT_FOUND,
                fingerprint = matchingSecret.sshKey?.fingerprint,
            )
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }
        currentCoroutineContext().ensureActive()
        // Check immediately before native signing; a lock cannot revoke work
        // that is already executing inside crypto.
        if (getVaultSession.valueOrNull !== currentVault.session) {
            return SshAgentRequestProcessor.SignDataResult.VaultLocked
        }

        return signWithPrivateKey(
            privateKey = currentPrivateKey,
            publicKey = currentSshKey.publicKey.orEmpty(),
            request = request,
        )
    }

    private fun signWithPrivateKey(
        privateKey: String,
        publicKey: String,
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentRequestProcessor.SignDataResult = try {
        // Every key type signs through the shared native (Rust) SSH engine, exactly
        // like the desktop `SshAgentRequestProcessorImpl`. The returned signature is
        // the RAW blob; the agent wraps it into the SSH wire format.
        val signature = NativeCrypto.ssh.sign(
            privateKeyPem = privateKey,
            publicKeyOpenSsh = publicKey,
            data = request.data,
            flags = request.flags,
        )
        SshAgentRequestProcessor.SignDataResult.Success(
            response = SshAgentMessages.SignDataResponse(
                signature = signature.signature,
                algorithm = signature.algorithm,
            ),
        )
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "SSH signing failed: ${e.message}", LogLevel.ERROR)
        SshAgentRequestProcessor.SignDataResult.Failure("SSH signing failed: ${e.message}")
    }

    private suspend fun getCachedSshKeys(): List<SshAgentPublicKeyRow> = try {
        sshAgentPublicKeyRepository.get()
            .bind()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached SSH public keys: ${e.message}", LogLevel.ERROR)
        emptyList()
    }

    private fun SshAgentPublicKeyRow.toSshKeyMessage() = SshAgentMessages.SshKey(
        name = displayName,
        publicKey = publicKey,
        keyType = keyType,
        fingerprint = fingerprint,
    )

    private suspend fun getSshKeysFromVault(
        session: MasterSession.Key? = getVaultSession.valueOrNull as? MasterSession.Key,
    ): SshVaultContext? {
        val key = session ?: return null
        if (getVaultSession.valueOrNull !== key) return null

        val getCiphers = key.sessionKoin.get<GetCiphers>()
        val sshKeys = getCiphers()
            .map { ciphers ->
                ciphers.filter { it.isEligibleForSshAgent() }
            }
            .first()
        val addSshUsageHistory = key.sessionKoin.getOrNull<AddSshUsageHistory>()
            ?: NoOpAddSshUsageHistory

        val filteredKeys = getSshAgentFilter().first().filterCiphers(
            context = key.sessionKoin.get(),
            ciphers = sshKeys,
        )
        if (getVaultSession.valueOrNull !== key) return null
        return SshVaultContext(
            session = key,
            sshKeys = filteredKeys,
            addSshUsageHistory = addSshUsageHistory,
        )
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

    private suspend fun requestSigningApproval(
        keyName: String,
        keyFingerprint: String,
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApproval(
                SshAgentApprovalInfo(
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    callerName = caller?.appName?.takeIf { it.isNotEmpty() }
                        ?: caller?.processName.orEmpty(),
                    callerPath = caller?.appBundlePath?.takeIf { it.isNotEmpty() }
                        ?: caller?.executablePath.orEmpty(),
                ),
            )
        } ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logRepository.post(TAG, "Approval request failed: ${e.message}", LogLevel.ERROR)
        false
    }

    private fun SshVaultContext.findKeyByPublicKey(publicKey: String, original: DSecret? = null): DSecret? = sshKeys
        .asSequence()
        .filter { original == null || (it.id == original.id && it.accountId == original.accountId) }
        .firstOrNull { secret ->
            val candidate = secret.sshKey?.publicKey ?: return@firstOrNull false
            sshPublicKeysMatch(candidate, publicKey)
        }

    private data class SshVaultContext(
        val session: MasterSession.Key,
        val sshKeys: List<DSecret>,
        val addSshUsageHistory: AddSshUsageHistory,
    )

    private object NoOpAddSshUsageHistory : AddSshUsageHistory {
        override fun invoke(request: AddSshUsageHistoryRequest): IO<Unit> = {
            // Do nothing
        }
    }
}
