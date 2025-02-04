package com.artemchep.keyguard.common.usecase.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.WithBiometric
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.memoize
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.security.KeyException

class EnableBiometricImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val getVaultSession: GetVaultSession,
    private val biometricStatusUseCase: BiometricStatusUseCase,
    private val biometricKeyEncryptUseCase: BiometricKeyEncryptUseCase,
) : EnableBiometric {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
        getVaultSession = directDI.instance(),
        biometricStatusUseCase = directDI.instance(),
        biometricKeyEncryptUseCase = directDI.instance(),
    )

    override fun invoke(
        masterSession: MasterSession.Key?,
    ) = ioEffect {
        val session = getVaultSession().toIO().bind()
        val tokens = keyReadWriteRepository.get().toIO().bind()
        require(tokens != null) {
            "Can not enable biometrics without a live session."
        }
        require(session is MasterSession.Key) {
            "Can not enable biometrics without a live session."
        }

        val biometric = biometricStatusUseCase().toIO().bind()
        require(biometric is BiometricStatus.Available) {
            "Can not enable biometrics on an unsupported device."
        }

        val getOrRecreateCipherForEncryption = biometric
            .createCipher
            .partially1(BiometricPurpose.Encrypt)
            // Flat map
            .let { createCipher ->
                // Lambda
                suspend {
                    try {
                        createCipher()
                    } catch (e: KeyException) {
                        // try to clear the cipher key...
                        biometric.deleteCipher()
                        // ...and recreate it.
                        createCipher()
                    }
                }
            }
            // Save the cipher for further use by
            // the code in the block. This allows us to not
            // return the cipher from the action.
            .memoize()
        WithBiometric(
            getCipher = getOrRecreateCipherForEncryption,
            getCreateIo = {
                val masterKey = session.masterKey
                val cipherIo = ioEffect { getOrRecreateCipherForEncryption() }
                biometricKeyEncryptUseCase(cipherIo, masterKey)
                    .effectMap { encryptedMasterKey ->
                        val cipher = getOrRecreateCipherForEncryption()

                        // Save new tokens to the repository.
                        val biometricTokens = FingerprintBiometric(
                            iv = cipher.iv,
                            encryptedMasterKey = encryptedMasterKey,
                        )
                        val newTokens = tokens.copy(biometric = biometricTokens)
                        keyReadWriteRepository.put(newTokens)
                    }
                    .flatten()
            },
        )
    }
}
