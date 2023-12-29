package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutCheckPwnedServicesImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPwnedServices {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(checkPwnedServices: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPwnedServices(checkPwnedServices)
}
