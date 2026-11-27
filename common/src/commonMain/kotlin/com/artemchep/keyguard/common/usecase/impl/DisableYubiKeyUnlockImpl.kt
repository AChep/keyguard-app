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
            if (tokens?.yubiKey != null) {
                // Clear the latest fingerprint: an outdated
                // copy may hold an old password.
                return@flatMap keyReadWriteRepository.update { latest ->
                    latest?.copy(yubiKey = null)
                }
            }

            ioUnit()
        }
}
