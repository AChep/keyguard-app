package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutBiometricRequireConfirmationImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBiometricRequireConfirmation {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(requireConfirmation: Boolean): IO<Unit> = settingsReadWriteRepository
        .setBiometricRequireConfirmation(requireConfirmation)
}
