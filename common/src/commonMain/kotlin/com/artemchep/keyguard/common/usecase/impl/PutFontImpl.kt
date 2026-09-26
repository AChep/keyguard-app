package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppFont
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutFont

class PutFontImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutFont {
    override fun invoke(font: AppFont?): IO<Unit> = settingsReadWriteRepository
        .setFont(font)
}
