package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.sshPublicKeysMatch
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.RSADigestSigner
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.util.encoders.Base64

class TestSshAgentRequestProcessor(
    private val keys: List<TestSshKey>,
) : SshAgentRequestProcessor {
    override suspend fun listKeys(
        caller: SshAgentMessages.CallerIdentity?,
    ): SshAgentRequestProcessor.ListKeysResult =
        SshAgentRequestProcessor.ListKeysResult.Success(
            response = SshAgentMessages.ListKeysResponse(
                keys = keys.map { key ->
                    SshAgentMessages.SshKey(
                        name = key.name,
                        publicKey = key.publicKey,
                        keyType = key.keyType,
                        fingerprint = key.fingerprint,
                    )
                },
            ),
        )

    override suspend fun signData(
        request: SshAgentMessages.SignDataRequest,
    ): SshAgentRequestProcessor.SignDataResult {
        val key = keys.firstOrNull { sshPublicKeysMatch(it.publicKey, request.publicKey) }
            ?: return SshAgentRequestProcessor.SignDataResult.KeyNotFound
        return try {
            SshAgentRequestProcessor.SignDataResult.Success(
                response = signWithPrivateKey(
                    privateKeyPem = key.privateKeyPem,
                    data = request.data,
                    flags = request.flags,
                ),
            )
        } catch (e: Exception) {
            SshAgentRequestProcessor.SignDataResult.Failure(
                message = "sign failed: ${e.message}\n${e.stackTraceToString()}",
            )
        }
    }

    private fun signWithPrivateKey(
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

        return when (val parsedKey = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(encodedPrivateKey)) {
            is Ed25519PrivateKeyParameters -> signEd25519(parsedKey, data)
            is RSAKeyParameters -> signRsa(parsedKey, data, flags)
            else -> throw IllegalArgumentException(
                "Unsupported key type: ${parsedKey::class.simpleName}",
            )
        }
    }

    private fun signEd25519(
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

    private fun signRsa(
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
}
