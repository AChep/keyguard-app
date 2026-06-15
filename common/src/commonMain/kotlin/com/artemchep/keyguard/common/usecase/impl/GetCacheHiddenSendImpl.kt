package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCacheHiddenSend
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCacheHiddenSendImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCacheHiddenSend {
    private val sharedFlow = settingsReadRepository
        .getCacheHiddenSend()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
