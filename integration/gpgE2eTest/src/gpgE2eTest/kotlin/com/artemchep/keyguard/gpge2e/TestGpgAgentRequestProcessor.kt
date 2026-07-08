package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.crypto.GpgAgentCryptoJvm

class TestGpgAgentRequestProcessor(
    private val keys: List<TestGpgKey>,
) : GpgAgentRequestProcessor {

    private val crypto = GpgAgentCryptoJvm()

    private data class KeyMatch(
        val key: TestGpgKey,
        val metadataKey: GpgAgentKeyMetadataKey,
    )

    private fun findByKeygrip(
        keygrip: String,
    ): KeyMatch? {
        val normalized = keygrip.trim().uppercase()
        for (key in keys) {
            for (metadataKey in key.metadataKeys) {
                if (metadataKey.keygrip.trim().uppercase() == normalized) {
                    return KeyMatch(key = key, metadataKey = metadataKey)
                }
            }
        }
        return null
    }

    override suspend fun listKeys(
        caller: GpgAgentMessages.CallerIdentity?,
    ): GpgAgentRequestProcessor.ListKeysResult {
        val gpgKeys = keys.flatMap { key ->
            key.metadataKeys.map { metadataKey ->
                GpgAgentMessages.GpgKey(
                    name = key.name,
                    keygrip = metadataKey.keygrip.trim().uppercase(),
                    fingerprint = metadataKey.fingerprint,
                    algorithm = metadataKey.algorithm,
                    canSign = metadataKey.canSign,
                    canDecrypt = metadataKey.canDecrypt,
                )
            }
        }
        return GpgAgentRequestProcessor.ListKeysResult.Success(
            response = GpgAgentMessages.ListKeysResponse(keys = gpgKeys),
        )
    }

    override suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.SignHashResponse> {
        val match = findByKeygrip(request.keygrip)
            ?: return GpgAgentRequestProcessor.GpgAgentOperationResult.KeyNotFound
        return try {
            val response = crypto.signHash(
                privateKeyArmored = match.key.privateKeyArmored,
                metadataKey = match.metadataKey,
                hashAlgorithm = request.hashAlgorithm,
                hash = request.hash,
            )
            GpgAgentRequestProcessor.GpgAgentOperationResult.Success(response = response)
        } catch (e: Exception) {
            GpgAgentRequestProcessor.GpgAgentOperationResult.Failure(
                message = "sign failed: ${e.message}\n${e.stackTraceToString()}",
            )
        }
    }

    override suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse> {
        val match = findByKeygrip(request.keygrip)
            ?: return GpgAgentRequestProcessor.GpgAgentOperationResult.KeyNotFound
        return try {
            val response = crypto.pkdecrypt(
                privateKeyArmored = match.key.privateKeyArmored,
                metadataKey = match.metadataKey,
                ciphertext = request.ciphertext,
            )
            GpgAgentRequestProcessor.GpgAgentOperationResult.Success(response = response)
        } catch (e: Exception) {
            GpgAgentRequestProcessor.GpgAgentOperationResult.Failure(
                message = "decrypt failed: ${e.message}\n${e.stackTraceToString()}",
            )
        }
    }
}
