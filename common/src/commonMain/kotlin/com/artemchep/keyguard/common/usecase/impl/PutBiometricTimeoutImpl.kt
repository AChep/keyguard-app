package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class PutBiometricTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBiometricTimeout {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setBiometricTimeout(duration)
}
