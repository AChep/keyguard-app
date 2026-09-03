package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DisableBiometricImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
    private val biometricKeyRepository: BiometricKeyRepository,
) : DisableBiometric {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
        biometricKeyRepository = directDI.instance(),
    )

    override fun invoke() = keyReadWriteRepository.get()
        .toIO()
        .flatMap { tokens ->
            val newTokens = tokens?.copy(biometric = null)
            val clearBindingIo = if (newTokens != tokens) {
                keyReadWriteRepository
                    .put(newTokens)
            } else {
                ioUnit()
            }
            clearBindingIo.flatTap {
                // The binding is gone at this point, so a leftover
                // platform key is harmless.
                biometricKeyRepository.delete()
                    .crashlyticsTap()
                    .attempt()
            }
        }
}
