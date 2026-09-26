package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import kotlin.time.Duration

class PutBiometricTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBiometricTimeout {
    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setBiometricTimeout(duration)
}
