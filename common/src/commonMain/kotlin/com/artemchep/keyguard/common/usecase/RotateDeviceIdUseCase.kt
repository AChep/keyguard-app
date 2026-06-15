package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatTap
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RotateDeviceIdUseCase(
    private val deviceIdUseCase: DeviceIdUseCase,
    private val removeAccounts: RemoveAccounts,
) : () -> IO<Unit> {
    constructor(directDI: DirectDI) : this(
        deviceIdUseCase = directDI.instance(),
        removeAccounts = directDI.instance(),
    )

    override fun invoke() = deviceIdUseCase
        .clear()
        // Using an old token with a new device identifiers upon next
        // request is suspicious.
        .flatTap {
            removeAccounts()
        }
}
