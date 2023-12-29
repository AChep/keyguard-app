package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.service.id.IdRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RotateDeviceIdUseCase(
    private val deviceIdRepository: IdRepository,
    private val removeAccounts: RemoveAccounts,
) : () -> IO<Unit> {
    constructor(directDI: DirectDI) : this(
        deviceIdRepository = directDI.instance(),
        removeAccounts = directDI.instance(),
    )

    override fun invoke() = deviceIdRepository
        .put("")
        // Using an old token with a new device identifiers upon next
        // request is suspicious.
        .flatTap {
            removeAccounts()
        }
}
