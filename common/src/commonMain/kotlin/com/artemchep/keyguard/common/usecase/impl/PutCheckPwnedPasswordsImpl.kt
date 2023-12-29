package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutCheckPwnedPasswordsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPwnedPasswords {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(checkPwnedPasswords: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPwnedPasswords(checkPwnedPasswords)
}
