package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.FingerprintYubiKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.YUBIKEY_UNLOCK_HKDF_INFO
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import org.kodein.di.DirectDI
import org.kodein.di.instance

class EnableYubiKeyUnlockImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val getVaultSession: GetVaultSession,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
) : EnableYubiKeyUnlock {
    companion object {
        private const val HKDF_LENGTH = 64
        private const val HKDF_SALT_LENGTH = 32
    }

    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
        getVaultSession = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        cipherEncryptor = directDI.instance(),
    )

    override fun invoke(
        slot: Int,
        challenge: ByteArray,
        response: ByteArray,
    ) = com.artemchep.keyguard.common.io.ioEffect {
        require(slot == 1 || slot == 2) {
            "YubiKey slot must be 1 or 2."
        }

        val session = getVaultSession().toIO().bind()
        val tokens = keyReadWriteRepository.get().toIO().bind()
        require(tokens != null) {
            "Can not enable YubiKey unlock without persisted vault data."
        }
        require(session is MasterSession.Key) {
            "Can not enable YubiKey unlock without a live session."
        }

        val hkdfSalt = cryptoGenerator.seed(length = HKDF_SALT_LENGTH)
        val wrappingKey = cryptoGenerator.hkdf(
            seed = response,
            salt = hkdfSalt,
            info = YUBIKEY_UNLOCK_HKDF_INFO.encodeToByteArray(),
            length = HKDF_LENGTH,
        )
        val encryptedMasterKey = cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = session.masterKey.byteArray,
            symmetricCryptoKey = SymmetricCryptoKey2(wrappingKey),
        )
        val yubiKey = FingerprintYubiKey(
            slot = slot,
            challenge = challenge,
            hkdfSalt = hkdfSalt,
            encryptedMasterKey = encryptedMasterKey,
        )
        keyReadWriteRepository.put(tokens.copy(yubiKey = yubiKey))
            .bind()
    }
}
