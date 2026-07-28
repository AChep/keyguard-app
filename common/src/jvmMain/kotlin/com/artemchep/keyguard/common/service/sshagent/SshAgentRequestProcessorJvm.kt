package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
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

class SshAgentRequestProcessorJvm(
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
    private val sessionId: String = "",
    private val json: Json = Json,
    private val onApprovalRequest: suspend (caller: SshAgentMessages.CallerIdentity?, keyName: String, keyFingerprint: String) -> Boolean =
        { _, _, _ -> true },
    private val onGetListRequest: suspend (caller: SshAgentMessages.CallerIdentity?) -> Boolean = { _ -> false },
) : SshAgentRequestProcessor {
    companion object {
        private const val TAG = "SshAgentRequestProcessor"

        internal const val APPROVAL_TIMEOUT_MS = 60_000L

        internal fun signWithPrivateKey(
            privateKeyPem: String,
            data: ByteArray,
            flags: Int,
            publicKeyOpenSsh: String? = null,
        ): SshAgentMessages.SignDataResponse {
            val result = NativeCrypto.ssh.sign(
                privateKeyPem = privateKeyPem,
                publicKeyOpenSsh = publicKeyOpenSsh,
                data = data,
                flags = flags,
            )
            return SshAgentMessages.SignDataResponse(
                signature = result.signature,
                algorithm = result.algorithm,
            )
        }

        internal fun extractKeyType(publicKey: String): String? =
            extractSshKeyType(publicKey)

        internal fun publicKeysMatch(
            left: String,
            right: String,
        ): Boolean = sshPublicKeysMatch(left, right)

        internal fun decodePublicKeyBlob(
            publicKey: String,
        ): ByteArray? = decodeSshPublicKeyBlob(publicKey)
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
            return SshAgentRequestProcessor.ListKeysResult.Success(
                response = SshAgentMessages.ListKeysResponse(
                    keys = keys,
                ),
            )
        }

        val keys = vault.sshKeys.mapNotNull { secret ->
            val sshKey = secret.sshKey ?: return@mapNotNull null
            val publicKey = sshKey.publicKey ?: return@mapNotNull null
            val keyType = extractKeyType(publicKey) ?: "unknown"
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
                keyName = cachedKey?.displayName ?: "SSH key",
                keyFingerprint = cachedKey?.fingerprint.orEmpty(),
                caller = request.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request while vault was locked", LogLevel.INFO)
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
            approvalGranted = true

            vault = getSshKeysFromVault()
            if (vault == null) {
                return SshAgentRequestProcessor.SignDataResult.VaultLocked
            }
            approvalAccess = vault.approvalWindowSession.access(request)
        }

        val availableSshKeys: List<DSecret> = vault.sshKeys
        val matchingSecret = availableSshKeys.find { secret ->
            val publicKey = secret.sshKey?.publicKey
                ?: return@find false
            publicKeysMatch(publicKey, request.publicKey)
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
                keyName = matchingSecret.name,
                keyFingerprint = sshKey.fingerprint ?: "",
                caller = request.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request", LogLevel.INFO)
                recordSshUsageSignData(SshUsageHistoryResponseType.USER_DENIED)
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
            approvalGranted = true
        }

        return try {
            val response = signWithPrivateKey(
                privateKeyPem = privateKeyPem,
                publicKeyOpenSsh = sshKey.publicKey,
                data = request.data,
                flags = request.flags,
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
        sshAgentPublicKeyRepository.get()
            .bind()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        logRepository.post(TAG, "Failed to read cached SSH public keys: ${e.message}", LogLevel.ERROR)
        emptyList()
    }

    private suspend fun getCachedSshKey(
        publicKey: String,
    ): SshAgentPublicKeyRow? = try {
        sshAgentPublicKeyRepository.getByPublicKey(publicKey)
            .bind()
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

        val sshAgentFilter = sshAgentFilterState.value.normalize()
        if (!sshAgentFilter.isActive) {
            return SshVaultContext(
                sshKeys = sshKeys,
                addSshUsageHistory = addSshUsageHistory,
                approvalWindowSession = approvalWindowSession,
            )
        }

        val predicate = sshAgentFilter.toDFilter().prepare(
            directDI = key.di.direct,
            ciphers = sshKeys,
        )
        return SshVaultContext(
            sshKeys = sshKeys.filter(predicate),
            addSshUsageHistory = addSshUsageHistory,
            approvalWindowSession = approvalWindowSession,
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
        keyName: String,
        keyFingerprint: String,
        caller: SshAgentMessages.CallerIdentity?,
    ): Boolean = try {
        withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            onApprovalRequest(
                caller,
                keyName,
                keyFingerprint,
            )
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
