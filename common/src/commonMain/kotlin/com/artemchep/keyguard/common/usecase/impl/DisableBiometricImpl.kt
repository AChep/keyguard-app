package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.DisableBiometric
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DisableBiometricImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
) : DisableBiometric {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
    )

    override fun invoke() = keyReadWriteRepository.get()
        .toIO()
        .flatMap { tokens ->
            val newTokens = tokens?.copy(biometric = null)
            if (newTokens != tokens) {
                return@flatMap keyReadWriteRepository
                    .put(newTokens)
            }

            ioUnit()
        }
}
