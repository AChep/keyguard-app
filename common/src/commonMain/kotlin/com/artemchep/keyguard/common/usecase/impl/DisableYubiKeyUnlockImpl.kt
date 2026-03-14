package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DisableYubiKeyUnlockImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
) : DisableYubiKeyUnlock {
    constructor(directDI: DirectDI) : this(
        keyReadWriteRepository = directDI.instance(),
    )

    override fun invoke() = keyReadWriteRepository.get()
        .toIO()
        .flatMap { tokens ->
            val newTokens = tokens?.copy(yubiKey = null)
            if (newTokens != tokens) {
                return@flatMap keyReadWriteRepository.put(newTokens)
            }

            ioUnit()
        }
}
