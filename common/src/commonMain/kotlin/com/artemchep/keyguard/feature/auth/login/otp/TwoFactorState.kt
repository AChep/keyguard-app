package com.artemchep.keyguard.feature.auth.login.otp

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow

data class TwoFactorState(
    val providers: ImmutableList<TwoFactorProviderItem> = persistentListOf(),
    val loadingState: StateFlow<Boolean>,
    val state: LoginTwofaState,
)
