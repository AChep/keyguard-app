package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock

class DisableYubiKeyUnlockImpl(
    private val keyReadWriteRepository: FingerprintReadWriteRepository,
) : DisableYubiKeyUnlock {
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
