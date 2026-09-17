package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys

class PutCheckPasskeysImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCheckPasskeys {
    override fun invoke(checkPasskeys: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCheckPasskeys(checkPasskeys)
}
