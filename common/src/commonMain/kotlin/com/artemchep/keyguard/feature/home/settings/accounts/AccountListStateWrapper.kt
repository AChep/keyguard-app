package com.artemchep.keyguard.feature.home.settings.accounts

fun interface AccountListStateWrapper {
    fun unwrap(
        onAddAccount: () -> Unit,
    ): AccountListState
}
