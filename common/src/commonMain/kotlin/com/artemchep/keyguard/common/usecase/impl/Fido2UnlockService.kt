package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintFido2
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.util.fido2.FIDO2_INPUT_LENGTH
import com.artemchep.keyguard.util.fido2.FIDO2_SECRET_LENGTH
import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlinx.coroutines.flow.first

private const val FIDO2_HKDF_INFO = "keyguard-fido2-unlock-v1"

/** Vault policy stays here; platform clients only create credentials and evaluate PRF. */
class Fido2UnlockService(
    private val fingerprints: FingerprintReadWriteRepository,
    private val getVaultSession: GetVaultSession,
    private val crypto: CryptoGenerator,
    private val cipher: CipherEncryptor,
    private val base64: Base64Service,
) {
    fun enroll(prompt: suspend (Fido2Operation) -> ByteArray) = ioEffect {
        val session = getVaultSession().first()
        require(session is MasterSession.Key)
        val before = requireNotNull(fingerprints.get().first())
        val credential =
            prompt(
                Fido2Operation.Register(
                    crypto.seed(FIDO2_INPUT_LENGTH),
                    crypto.seed(FIDO2_INPUT_LENGTH),
                )
            )
        val salt = crypto.seed(FIDO2_INPUT_LENGTH)
        // Derive in a separate ceremony: CTAP 2.0 keys need not support PRF during registration.
        val secret =
            prompt(Fido2Operation.Derive(credential, salt, crypto.seed(FIDO2_INPUT_LENGTH)))
        try {
            require(secret.size == FIDO2_SECRET_LENGTH)
            val protector = createProtector(session.masterKey, credential, salt, secret)
            // Validate inside the write transaction, so a password change cannot be overwritten.
            fingerprints
                .update { current ->
                    check(getVaultSession.valueOrNull === session)
                    check(
                        current != null &&
                            current.version == before.version &&
                            current.master == before.master
                    )
                    current.copy(fido2 = protector)
                }
                .bind()
        } finally {
            secret.fill(0)
        }
    }

    fun disable() =
        fingerprints.update { current ->
            require(getVaultSession.valueOrNull is MasterSession.Key)
            requireNotNull(current).copy(fido2 = null)
        }

    fun decrypt(tokens: Fingerprint, secret: ByteArray): MasterKey {
        require(secret.size == FIDO2_SECRET_LENGTH)
        val protector = requireNotNull(tokens.fido2).also { it.validate(base64) }
        return withWrappingKey(secret, base64.decode(protector.hkdfSalt)) { key ->
            val bytes = cipher.decode2(protector.encryptedMasterKey, key).data
            MasterKey(version = tokens.version, byteArray = bytes)
        }
    }

    private fun createProtector(
        masterKey: MasterKey,
        credential: ByteArray,
        salt: ByteArray,
        secret: ByteArray,
    ): FingerprintFido2 {
        val hkdfSalt = crypto.seed(FIDO2_INPUT_LENGTH)
        val encrypted =
            withWrappingKey(secret, hkdfSalt) { key ->
                cipher.encode2(
                    CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                    masterKey.byteArray,
                    key,
                )
            }
        return FingerprintFido2(
                credentialId = base64.encodeToString(credential),
                salt = base64.encodeToString(salt),
                hkdfSalt = base64.encodeToString(hkdfSalt),
                encryptedMasterKey = encrypted,
            )
            .also { it.validate(base64) }
    }

    private inline fun <T> withWrappingKey(
        secret: ByteArray,
        salt: ByteArray,
        block: (SymmetricCryptoKey2) -> T,
    ): T {
        val bytes =
            crypto.hkdf(
                seed = secret,
                salt = salt,
                info = FIDO2_HKDF_INFO.encodeToByteArray(),
                length = 64,
            )
        val key = SymmetricCryptoKey2(bytes)
        val parts = key.requireAesCbc256_HmacSha256_B64()
        return try {
            block(key)
        } finally {
            bytes.fill(0)
            parts.encKey.fill(0)
            parts.macKey.fill(0)
        }
    }
}
