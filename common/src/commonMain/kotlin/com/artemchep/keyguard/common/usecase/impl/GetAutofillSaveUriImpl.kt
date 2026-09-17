package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetCanWrite
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillSaveUriImpl(
    settingsReadRepository: SettingsReadRepository,
    private val getCanWrite: GetCanWrite,
) : GetAutofillSaveUri {
    private val sharedFlow = settingsReadRepository.getAutofillSaveUri()
        .distinctUntilChanged()

    override fun invoke() = combine(
        sharedFlow,
        getCanWrite(),
    ) { saveRequest, canWrite ->
        saveRequest && canWrite
    }
}
