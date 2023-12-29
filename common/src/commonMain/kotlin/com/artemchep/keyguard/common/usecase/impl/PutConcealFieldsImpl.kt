package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutConcealFields
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutConcealFieldsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutConcealFields {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(concealFields: Boolean): IO<Unit> = settingsReadWriteRepository
        .setConcealFields(concealFields)
}
