package com.artemchep.keyguard.feature.home.settings.accounts

import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType

fun interface AccountListStateWrapper {
    fun unwrap(
        onAddAccount: (AccountType) -> Unit,
    ): AccountListState
}
