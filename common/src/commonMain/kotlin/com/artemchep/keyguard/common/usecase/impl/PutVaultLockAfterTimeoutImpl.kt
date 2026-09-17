package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import kotlin.time.Duration

class PutVaultLockAfterTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterTimeout {
    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setVaultTimeout(duration)
}
