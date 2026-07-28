package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.sshPublicKeysMatch
import com.artemchep.keyguard.nativecrypto.NativeCrypto

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
                response = NativeCrypto.ssh.sign(
                    privateKeyPem = key.privateKeyPem,
                    publicKeyOpenSsh = key.publicKey,
                    data = request.data,
                    flags = request.flags,
                ).let { result ->
                    SshAgentMessages.SignDataResponse(
                        signature = result.signature,
                        algorithm = result.algorithm,
                    )
                },
            )
        } catch (e: Exception) {
            SshAgentRequestProcessor.SignDataResult.Failure(
                message = "sign failed: ${e.message}\n${e.stackTraceToString()}",
            )
        }
    }

}
