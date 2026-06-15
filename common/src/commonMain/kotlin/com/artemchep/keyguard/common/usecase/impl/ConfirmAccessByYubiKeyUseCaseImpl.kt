package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.YubiKeyUnlockDecryptException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.YUBIKEY_UNLOCK_HKDF_INFO
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyRequest
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyUseCase
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ConfirmAccessByYubiKeyUseCaseImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val getVaultSession: GetVaultSession,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
    private val yubiKeyUnlockAvailability: YubiKeyUnlockAvailability,
) : ConfirmAccessByYubiKeyUseCase {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
        getVaultSession = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        cipherEncryptor = directDI.instance(),
        yubiKeyUnlockAvailability = directDI.instance(),
    )

    override fun invoke(): IO<ConfirmAccessByYubiKeyRequest?> = ioEffect {
        if (!yubiKeyUnlockAvailability.isSupported()) {
            return@ioEffect null
        }

        val session = getVaultSession().toIO().bind()
        if (session !is MasterSession.Key) {
            return@ioEffect null
        }

        val fingerprint = keyReadWriteRepository.get().toIO().bind()
            ?: return@ioEffect null
        val protector = fingerprint.yubiKey
            ?: return@ioEffect null

        ConfirmAccessByYubiKeyRequest(
            slot = protector.slot,
            challenge = protector.challenge,
            confirm = { response ->
                confirmYubiKeyResponse(
                    fingerprint = fingerprint,
                    session = session,
                    response = response,
                )
            },
        )
    }

    private fun confirmYubiKeyResponse(
        fingerprint: Fingerprint,
        session: MasterSession.Key,
        response: ByteArray,
    ): IO<Unit> = decryptYubiKeyMasterKey(
        fingerprint = fingerprint,
        response = response,
    ).flatMap { restoredMasterKey ->
        if (restoredMasterKey == session.masterKey) {
            ioUnit()
        } else {
            ioRaise(
                YubiKeyUnlockDecryptException(
                    IllegalStateException("YubiKey response does not match the active session."),
                ),
            )
        }
    }

    private fun decryptYubiKeyMasterKey(
        fingerprint: Fingerprint,
        response: ByteArray,
    ): IO<MasterKey> = ioEffect {
        val protector = requireNotNull(fingerprint.yubiKey)
        val wrappingKey = cryptoGenerator.hkdf(
            seed = response,
            salt = protector.hkdfSalt,
            info = YUBIKEY_UNLOCK_HKDF_INFO.encodeToByteArray(),
            length = 64,
        )
        val plainBytes = cipherEncryptor.decode2(
            cipher = protector.encryptedMasterKey,
            symmetricCryptoKey = SymmetricCryptoKey2(wrappingKey),
        ).data
        MasterKey(
            version = fingerprint.version,
            byteArray = plainBytes,
        )
    }.handleErrorWith { e ->
        if (e is YubiKeyUnlockDecryptException) {
            ioRaise(e)
        } else {
            ioRaise(YubiKeyUnlockDecryptException(e))
        }
    }
}
