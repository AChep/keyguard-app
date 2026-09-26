package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices

class PutCheckPwnedServicesImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPwnedServices {
    override fun invoke(checkPwnedServices: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPwnedServices(checkPwnedServices)
}
