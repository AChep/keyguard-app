package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCanAddAccount
import com.artemchep.keyguard.common.usecase.GetPurchased
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCanAddAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
) : GetCanAddAccount {
    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
        getAccounts = directDI.instance(),
    )

    override fun invoke() = combine(
        getPurchased(),
        getAccounts()
            .map { it.isNotEmpty() }
            .distinctUntilChanged(),
    ) { isPremium, hasAccount ->
        isPremium || !hasAccount
    }
}
