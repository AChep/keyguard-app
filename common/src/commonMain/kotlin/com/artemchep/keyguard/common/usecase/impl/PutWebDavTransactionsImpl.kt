package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutWebDavTransactions

class PutWebDavTransactionsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutWebDavTransactions {
    override fun invoke(webDavTransactions: Boolean): IO<Unit> = settingsReadWriteRepository
        .setWebDavTransactions(webDavTransactions)
}
