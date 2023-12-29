package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetCanWrite
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillSaveRequestImpl(
    settingsReadRepository: SettingsReadRepository,
    private val getCanWrite: GetCanWrite,
) : GetAutofillSaveRequest {
    private val sharedFlow = settingsReadRepository.getAutofillSaveRequest()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
        getCanWrite = directDI.instance(),
    )

    override fun invoke() = combine(
        sharedFlow,
        getCanWrite(),
    ) { saveRequest, canWrite ->
        saveRequest && canWrite
    }
}
