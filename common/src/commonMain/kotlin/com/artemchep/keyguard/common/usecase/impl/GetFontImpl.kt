package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetFont
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetFontImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetFont {
    private val sharedFlow = settingsReadRepository.getFont()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<AppFont?> = sharedFlow
}
