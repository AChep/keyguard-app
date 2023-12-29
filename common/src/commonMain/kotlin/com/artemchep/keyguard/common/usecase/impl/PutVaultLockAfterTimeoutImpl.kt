package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class PutVaultLockAfterTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterTimeout {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setVaultTimeout(duration)
}
