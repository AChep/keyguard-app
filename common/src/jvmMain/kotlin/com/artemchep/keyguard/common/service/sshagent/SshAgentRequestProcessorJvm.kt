package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature as JcaSignature
import java.security.spec.PKCS8EncodedKeySpec

class SshAgentRequestProcessorJvm(
    private val logRepository: LogRepository,
    private val getVaultSession: GetVaultSession,
    private val getSshAgentFilter: GetSshAgentFilter,
    scope: CoroutineScope,
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

    override suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult {
        val sshKeys = getSshKeysFromVaultOrRequestGetList(caller)
            ?: return SshAgentRequestProcessor.ListKeysResult.VaultLocked

        val keys = sshKeys.mapNotNull { secret ->
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

        return SshAgentRequestProcessor.ListKeysResult.Success(
            response = SshAgentMessages.ListKeysResponse(keys = keys),
        )
    }

    override suspend fun signData(
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentRequestProcessor.SignDataResult {
        var sshKeys = getSshKeysFromVault()
        val wasVaultLocked = sshKeys == null

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

            sshKeys = getSshKeysFromVault()
            if (sshKeys == null) {
                return SshAgentRequestProcessor.SignDataResult.VaultLocked
            }
        }

        val availableSshKeys: List<DSecret> = sshKeys
        val matchingSecret = availableSshKeys.find { secret ->
            val publicKey = secret.sshKey?.publicKey ?: return@find false
            publicKeysMatch(publicKey, request.publicKey)
        } ?: return SshAgentRequestProcessor.SignDataResult.KeyNotFound

        val sshKey = matchingSecret.sshKey ?: return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        val privateKeyPem = sshKey.privateKey
        if (privateKeyPem.isNullOrBlank()) {
            return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        }

        if (!wasVaultLocked) {
            val approved = requestSigningApproval(
                keyName = matchingSecret.name,
                keyFingerprint = sshKey.fingerprint ?: "",
                caller = request.caller,
            )
            if (!approved) {
                logRepository.post(TAG, "User denied the signing request", LogLevel.INFO)
                return SshAgentRequestProcessor.SignDataResult.UserDenied
            }
        }

        return try {
            SshAgentRequestProcessor.SignDataResult.Success(
                response = signWithPrivateKey(
                    privateKeyPem = privateKeyPem,
                    data = request.data,
                    flags = request.flags,
                ),
            )
        } catch (e: Exception) {
            logRepository.post(TAG, "Signing failed: ${e.message}", LogLevel.ERROR)
            SshAgentRequestProcessor.SignDataResult.Failure(
                message = "Signing failed: ${e.message}",
            )
        }
    }

    private suspend fun getSshKeysFromVault(): List<DSecret>? {
        val session = getVaultSession.valueOrNull
        val key = session as? MasterSession.Key ?: return null

        val getCiphers = key.di.direct.instance<GetCiphers>()
        val sshKeys = getCiphers()
            .map { ciphers ->
                ciphers.filter { it.isEligibleForSshAgent() }
            }
            .first()

        val sshAgentFilter = sshAgentFilterState.value.normalize()
        if (!sshAgentFilter.isActive) {
            return sshKeys
        }

        val predicate = sshAgentFilter.toDFilter().prepare(
            directDI = key.di.direct,
            ciphers = sshKeys,
        )
        return sshKeys.filter(predicate)
    }

    private suspend fun getSshKeysFromVaultOrRequestGetList(
        caller: SshAgentMessages.CallerIdentity?,
    ): List<DSecret>? {
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
}
