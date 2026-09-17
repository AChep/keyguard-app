package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetConcealFields
import kotlinx.coroutines.flow.distinctUntilChanged

class GetConcealFieldsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetConcealFields {
    private val sharedFlow = settingsReadRepository.getConcealFields()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
