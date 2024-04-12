package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutCheckPasskeysImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPasskeys {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(checkPasskeys: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPasskeys(checkPasskeys)
}
