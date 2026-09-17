package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords

class PutCheckPwnedPasswordsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPwnedPasswords {
    override fun invoke(checkPwnedPasswords: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPwnedPasswords(checkPwnedPasswords)
}
