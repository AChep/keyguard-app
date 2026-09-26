package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatTap

class RotateDeviceIdUseCase(
    private val deviceIdUseCase: DeviceIdUseCase,
    private val removeAccounts: RemoveAccounts,
) : () -> IO<Unit> {
    override fun invoke() = deviceIdUseCase
        .clear()
        // Using an old token with a new device identifiers upon next
        // request is suspicious.
        .flatTap {
            removeAccounts()
        }
}
