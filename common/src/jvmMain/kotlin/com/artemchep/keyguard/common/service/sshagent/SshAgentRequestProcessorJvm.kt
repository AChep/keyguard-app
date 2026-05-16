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
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.util.encoders.Base64
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature as JcaSignature
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SshAgentRequestProcessorJvm(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    private val getSshAgentFilter: GetSshAgentFilter,
    scope: CoroutineScope,
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
        ): SshAgentMessages.SignDataResponse {
            val encodedPrivateKey = privateKeyPem
                .replace("-{1,5}(BEGIN|END) (|RSA |OPENSSH )PRIVATE KEY-{1,5}".toRegex(), "")
                .lineSequence()
                .map { it.trim() }
                .joinToString(separator = "")
                .let { Base64.decode(it) }

            val parsedKey = kotlin.runCatching {
                OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(encodedPrivateKey)
            }.getOrNull()

            if (parsedKey != null) {
                return when (parsedKey) {
                    is Ed25519PrivateKeyParameters -> signEd25519(parsedKey, data)
                    is RSAPrivateCrtKeyParameters -> signRsa(parsedKey, data, flags)
                    is RSAKeyParameters -> signRsa(parsedKey, data, flags)
                    else -> throw IllegalArgumentException(
                        "Unsupported key type: ${parsedKey::class.simpleName}",
                    )
                }
            }

            val jcaPrivateKey = parseJcaPrivateKey(encodedPrivateKey)
            return when (jcaPrivateKey.algorithm) {
                "RSA" -> signRsaJca(jcaPrivateKey, data, flags)
                else -> throw IllegalArgumentException(
                    "Unsupported key type: ${jcaPrivateKey.algorithm}",
                )
            }
        }

        internal fun signEd25519(
            privateKey: Ed25519PrivateKeyParameters,
            data: ByteArray,
        ): SshAgentMessages.SignDataResponse {
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            signer.update(data, 0, data.size)
            return SshAgentMessages.SignDataResponse(
                signature = signer.generateSignature(),
                algorithm = "ssh-ed25519",
            )
        }

        internal fun signRsa(
            privateKey: RSAKeyParameters,
            data: ByteArray,
            flags: Int,
        ): SshAgentMessages.SignDataResponse {
            val (algorithm, digest) = when {
                flags and 0x04 != 0 -> "rsa-sha2-512" to SHA512Digest()
                flags and 0x02 != 0 -> "rsa-sha2-256" to SHA256Digest()
                else -> "ssh-rsa" to SHA1Digest()
            }

            val signer = RSADigestSigner(digest)
            signer.init(true, privateKey)
            signer.update(data, 0, data.size)
            return SshAgentMessages.SignDataResponse(
                signature = signer.generateSignature(),
                algorithm = algorithm,
            )
        }

        internal fun signRsaJca(
            privateKey: PrivateKey,
            data: ByteArray,
            flags: Int,
        ): SshAgentMessages.SignDataResponse {
            val (algorithm, jcaAlgorithm) = when {
                flags and 0x04 != 0 -> "rsa-sha2-512" to "SHA512withRSA"
                flags and 0x02 != 0 -> "rsa-sha2-256" to "SHA256withRSA"
                else -> "ssh-rsa" to "SHA1withRSA"
            }

            val signer = JcaSignature.getInstance(jcaAlgorithm)
            signer.initSign(privateKey)
            signer.update(data)
            return SshAgentMessages.SignDataResponse(
                signature = signer.sign(),
                algorithm = algorithm,
            )
        }

        internal fun parseJcaPrivateKey(
            encodedPrivateKey: ByteArray,
        ): PrivateKey {
            val spec = PKCS8EncodedKeySpec(encodedPrivateKey)
            return KeyFactory.getInstance("RSA")
                .generatePrivate(spec)
        }

        internal fun extractKeyType(publicKey: String): String? =
            publicKey.trim().splitToSequence(Regex("\\s+")).firstOrNull()

        internal fun publicKeysMatch(
            left: String,
            right: String,
        ): Boolean {
            val leftBlob = decodePublicKeyBlob(left) ?: return false
            val rightBlob = decodePublicKeyBlob(right) ?: return false
            return leftBlob.contentEquals(rightBlob)
        }

        internal fun decodePublicKeyBlob(
            publicKey: String,
        ): ByteArray? = runCatching {
            val encodedKeyBase64 = publicKey
                .trim()
                .splitToSequence(Regex("\\s+"))
                .drop(1)
                .firstOrNull()
                ?.trim()
            if (encodedKeyBase64.isNullOrEmpty()) {
                null
            } else {
                Base64.decode(encodedKeyBase64)
            }
        }.getOrNull()
    }

    private val sshAgentFilterState = getSshAgentFilter()
        .stateIn(scope, SharingStarted.Eagerly, SshAgentFilter())

    private val approvalWindowMemory = SshAgentApprovalWindowMemory(
        getSshAgentApprovalWindow = getSshAgentApprovalWindow,
        getVaultSession = getVaultSession,
        scope = scope,
    )

    override suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult {
        val vault = getSshKeysFromVaultOrRequestGetList(caller)
            ?: return SshAgentRequestProcessor.ListKeysResult.VaultLocked

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

        val approvalRemembered = vault?.approvalWindowSession?.isRemembered(request) == true
        var approvalGranted = false

        if (wasVaultLocked) {
            logRepository.post(TAG, "Vault is locked, requesting approval before SSH signing", LogLevel.INFO)
            val approved = requestSigningApproval(
                keyName = "SSH key",
                keyFingerprint = "",
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
                data = request.data,
                flags = request.flags,
            )
            if (approvalGranted) {
                vault.approvalWindowSession.remember(request)
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

    private suspend fun getSshKeysFromVaultOrRequestGetList(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshVaultContext? {
        getSshKeysFromVault()?.let { return it }

        logRepository.post(TAG, "Vault is locked, requesting list-keys unlock from user", LogLevel.INFO)
        val unlocked = requestVaultUnlock(caller)
        if (!unlocked) {
            logRepository.post(TAG, "User did not unlock the vault", LogLevel.INFO)
            return null
        }

        logRepository.post(TAG, "Vault unlocked, retrying key retrieval", LogLevel.INFO)
        return getSshKeysFromVault()
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

private class SshAgentApprovalWindowMemory(
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
) {
    private val approvalWindowState = getSshAgentApprovalWindow()
        .stateIn(scope, SharingStarted.Eagerly, Duration.ZERO)

    private val mutex = Mutex()
    private val cache = mutableMapOf<ApprovalCacheKey, ApprovalCacheEntry>()
    private var activeSession: ActiveSession? = null
    private var sessionGeneration = 0L

    init {
        // Changing the window invalidates approvals granted under older rules.
        var previousApprovalWindow = approvalWindowState.value
        approvalWindowState
            .onEach { approvalWindow ->
                val hasChanged = approvalWindow != previousApprovalWindow
                previousApprovalWindow = approvalWindow

                if (hasChanged || approvalWindow <= Duration.ZERO) {
                    clearCache()
                }
            }
            .launchIn(scope)

        // A remembered approval belongs only to the currently unlocked vault.
        getVaultSession()
            .onEach { session ->
                val key = session as? MasterSession.Key
                if (key != null) {
                    getOrGenerateSession(key)
                } else {
                    clearSession()
                }
            }
            .launchIn(scope)
    }

    suspend fun clearSession() {
        mutex.withLock {
            activeSession = null
            cache.clear()
        }
    }

    suspend fun getOrGenerateSession(
        session: MasterSession.Key,
    ): Session = mutex.withLock {
        activeSession
            ?.takeIf { it.session === session }
            ?.let { return@withLock Session(generation = it.generation) }

        // Use identity equality above: a new MasterSession.Key means a new unlock.
        val generation = sessionGeneration + 1L
        sessionGeneration = generation
        activeSession = ActiveSession(
            session = session,
            generation = generation,
        )
        cache.clear()
        Session(generation = generation)
    }

    private suspend fun clearCache() {
        mutex.withLock {
            cache.clear()
        }
    }

    private suspend fun isRemembered(
        session: Session,
        request: SshAgentMessages.SignDataRequest,
    ): Boolean {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return false
        }

        val key = request.toApprovalCacheKey(session)
        return mutex.withLock {
            val entry = cache[key]
                ?: return@withLock false

            if (entry.approvalWindow != approvalWindow || entry.isExpired()) {
                cache.remove(key)
                false
            } else {
                true
            }
        }
    }

    private suspend fun remember(
        session: Session,
        request: SshAgentMessages.SignDataRequest,
    ) {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return
        }

        val key = request.toApprovalCacheKey(session)
        val entry = ApprovalCacheEntry(
            approvalWindow = approvalWindow,
            expiresAt = approvalWindow
                .takeUnless { it == Duration.INFINITE }
                ?.let { TimeSource.Monotonic.markNow() + it },
        )
        mutex.withLock {
            // Re-check after suspension so stale approvals are not written back.
            val currentApprovalWindow = approvalWindowState.value
            if (currentApprovalWindow != approvalWindow || currentApprovalWindow <= Duration.ZERO) {
                return
            }

            if (activeSession?.generation != session.generation) {
                return
            }

            cache[key] = entry
        }
    }

    private fun SshAgentMessages.SignDataRequest.toApprovalCacheKey(
        session: Session,
    ): ApprovalCacheKey {
        val publicKeyToken = SshAgentRequestProcessorJvm.decodePublicKeyBlob(publicKey)
            ?.toHex()
            ?: publicKey.trim()
        return ApprovalCacheKey(
            sessionGeneration = session.generation,
            publicKeyToken = publicKeyToken,
            callerToken = caller.toApprovalCacheToken(),
        )
    }

    private fun SshAgentMessages.CallerIdentity?.toApprovalCacheToken(): String {
        // At this moment we are not precise at all with the caller
        // identity, so for now just use a generic name of the app.
        return "generic-caller=${this?.appName.orEmpty()}"
    }

    inner class Session internal constructor(
        val generation: Long,
    ) {
        suspend fun isRemembered(
            request: SshAgentMessages.SignDataRequest,
        ): Boolean = this@SshAgentApprovalWindowMemory.isRemembered(this, request)

        suspend fun remember(
            request: SshAgentMessages.SignDataRequest,
        ) {
            this@SshAgentApprovalWindowMemory.remember(this, request)
        }
    }

    private data class ActiveSession(
        val session: MasterSession.Key,
        val generation: Long,
    )

    private data class ApprovalCacheKey(
        val sessionGeneration: Long,
        val publicKeyToken: String,
        val callerToken: String,
    )

    private data class ApprovalCacheEntry(
        val approvalWindow: Duration,
        val expiresAt: TimeMark?,
    ) {
        fun isExpired(): Boolean = expiresAt?.hasPassedNow() == true
    }
}
