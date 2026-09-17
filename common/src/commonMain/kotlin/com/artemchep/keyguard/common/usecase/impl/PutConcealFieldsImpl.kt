package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutConcealFields

class PutConcealFieldsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutConcealFields {
    override fun invoke(concealFields: Boolean): IO<Unit> = settingsReadWriteRepository
        .setConcealFields(concealFields)
}
