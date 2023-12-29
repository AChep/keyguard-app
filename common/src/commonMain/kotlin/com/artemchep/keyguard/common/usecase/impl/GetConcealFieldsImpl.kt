package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetConcealFields
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetConcealFieldsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetConcealFields {
    private val sharedFlow = settingsReadRepository.getConcealFields()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
