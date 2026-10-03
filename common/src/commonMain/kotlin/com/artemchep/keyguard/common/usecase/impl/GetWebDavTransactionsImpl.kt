package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetWebDavTransactions
import kotlinx.coroutines.flow.distinctUntilChanged

class GetWebDavTransactionsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetWebDavTransactions {
    private val sharedFlow = settingsReadRepository.getWebDavTransactions()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
