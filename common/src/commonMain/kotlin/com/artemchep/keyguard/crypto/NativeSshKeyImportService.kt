package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeSshKeyMaterial
import com.artemchep.keyguard.nativecrypto.NativeSshPrivateKeyImportError
import com.artemchep.keyguard.nativecrypto.NativeSshPrivateKeyImportResult

object NativeSshKeyImportService : SshKeyImportService {
    override fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult {
        val result = NativeCrypto.ssh.importPrivateKey(
            content = request.content,
            passphrase = request.passphrase,
        )
        return when (result) {
            is NativeSshPrivateKeyImportResult.Success -> result.keyMaterial.toKeyPairResult()
            is NativeSshPrivateKeyImportResult.NeedsPassphrase ->
                SshKeyImportResult.NeedsPassphrase(result.formatLabel)

            is NativeSshPrivateKeyImportResult.Error -> SshKeyImportResult.Error(
                reason = result.reason.toDomain(),
            )
        }
    }

    private fun NativeSshKeyMaterial.toKeyPairResult(): SshKeyImportResult.Success = try {
        val description = NativeCrypto.ssh.describe(
            type = type,
            privateKey = privateKey,
            publicKey = publicKey,
        )
        val domainType = type.toDomain()
        SshKeyImportResult.Success(
            KeyPair(
                type = domainType,
                publicKey = KeyPair.KeyParameter(
                    encoded = publicKey,
                    type = domainType,
                    ssh = description.publicKeyOpenSsh,
                    fingerprint = description.publicFingerprint,
                ),
                privateKey = KeyPair.KeyParameter(
                    encoded = privateKey,
                    type = domainType,
                    ssh = description.privateKeyPem,
                    fingerprint = description.privateFingerprint,
                ),
            ),
        )
    } catch (failure: Throwable) {
        privateKey.fill(0)
        publicKey.fill(0)
        throw failure
    }
}

private fun NativeSshPrivateKeyImportError.toDomain(): SshKeyImportError = when (this) {
    NativeSshPrivateKeyImportError.UNSUPPORTED_FORMAT -> SshKeyImportError.UnsupportedFormat
    NativeSshPrivateKeyImportError.UNSUPPORTED_ALGORITHM -> SshKeyImportError.UnsupportedAlgorithm
    NativeSshPrivateKeyImportError.INVALID_PASSPHRASE -> SshKeyImportError.InvalidPassphrase
    NativeSshPrivateKeyImportError.MALFORMED_KEY -> SshKeyImportError.MalformedKey
}
